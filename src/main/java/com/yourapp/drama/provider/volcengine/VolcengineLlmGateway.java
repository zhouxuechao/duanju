package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.provider.StructuredJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VolcengineLlmGateway implements LlmGateway {
    private final ArkHttpClient http;
    private final VolcengineProperties properties;
    private final StructuredJson structuredJson;
    public VolcengineLlmGateway(ArkHttpClient http, VolcengineProperties properties, StructuredJson structuredJson) {
        this.http = http; this.properties = properties; this.structuredJson = structuredJson;
    }
    @Override public <T> StructuredResult<T> generate(StructuredRequest request, Class<T> responseType) {
        ProviderInputs.prompt(request.userPrompt());
        JsonNode schema = structuredJson.schema(request.jsonSchema());
        boolean director="director".equals(request.options().get("modelRole"));
        String model=director&&properties.getDirectorModel()!=null&&!properties.getDirectorModel().isBlank()?properties.getDirectorModel():properties.getTextModel();
        String apiStyle=director&&properties.getDirectorModel()!=null&&!properties.getDirectorModel().isBlank()?properties.getDirectorApiStyle():properties.getTextApiStyle();
        boolean responses = apiStyle.equals("responses");
        boolean deepseek = model.toLowerCase(java.util.Locale.ROOT).startsWith("deepseek-");
        Map<String, Object> body = ProviderInputs.options(request.options(), responses
                ? Set.of("imageUrls", "temperature", "top_p", "max_output_tokens", "thinking", "reasoning", "modelRole")
                : Set.of("imageUrls", "temperature", "top_p", "max_tokens", "thinking", "reasoning_effort", "modelRole"));
        body.remove("modelRole");
        Object images = body.remove("imageUrls");
        List<String> imageUrls = new ArrayList<>();
        if (images != null) {
            if (!(images instanceof List<?> list)) throw ProviderException.invalid("INVALID_IMAGE_URLS", "imageUrls 必须是 URL 列表");
            for (Object image : list) {
                if (!(image instanceof String url)) throw ProviderException.invalid("INVALID_IMAGE_URLS", "imageUrls 只能包含字符串");
                ProviderInputs.imageUrl(url, false); imageUrls.add(url);
            }
        }
        body.put("model", model); body.put("stream", false);
        body.putIfAbsent("thinking", Map.of("type", "disabled"));
        body.putIfAbsent(responses ? "max_output_tokens" : "max_tokens", properties.getMaxOutputTokens());
        String system = request.systemPrompt() == null ? "请只输出符合 JSON Schema 的 JSON。" : request.systemPrompt();
        // JSON mode guarantees JSON syntax only; the model also needs the same
        // field contract that StructuredJson validates after receiving the response.
        if (deepseek && !responses) {
            system += "\n\n输出必须符合以下 JSON Schema（保留字段原名和层级，不要增加外层包装）：\n" + schema.toString();
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", responses ? "input_text" : "text", "text", request.userPrompt()));
        for (String url : imageUrls) content.add(responses ? Map.of("type", "input_image", "image_url", url)
                : Map.of("type", "image_url", "image_url", Map.of("url", url)));
        if (responses) {
            body.put("input", List.of(Map.of("role", "system", "content", List.of(Map.of("type", "input_text", "text", system))),
                    Map.of("role", "user", "content", content)));
            body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "drama_output", "strict", true, "schema", request.jsonSchema())));
            body.put("store", false);
        } else {
            body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", content)));
            body.put("response_format", deepseek ? Map.of("type", "json_object")
                    : Map.of("type", "json_schema", "json_schema", Map.of("name", "drama_output", "strict", true, "schema", request.jsonSchema())));
        }
        ArkHttpClient.Response result = http.exchange("POST", responses ? "/responses" : "/chat/completions", body, true);
        String output;
        String finishReason;
        if (responses) {
            String status = ArkHttpClient.text(result.json().path("status"));
            finishReason=status==null?"completed":status;
            if (status != null && !status.equals("completed")) {
                String reason = ArkHttpClient.text(result.json().path("incomplete_details").path("reason"));
                if (status.equals("incomplete") || truncated(reason))
                    throw truncated(result, reason == null ? status : reason);
                String suffix = reason == null ? "" : "（原因：" + reason + "）";
                throw invalid(result.requestId(), "模型输出未完成" + suffix + "；请检查服务状态").withRawOutput(result.json().toString());
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : result.json().path("output")) for (JsonNode part : item.path("content"))
                if (part.path("type").asText().equals("output_text")) text.append(part.path("text").asText());
            output = text.toString();
        } else {
            JsonNode choice = result.json().path("choices").path(0);
            String finish = choice.path("finish_reason").asText();
            finishReason=finish;
            if (!finish.equals("stop")) {
                if (truncated(finish)) throw truncated(result, finish);
                throw invalid(result.requestId(), "模型输出未正常完成：" + finish).withRawOutput(result.json().toString());
            }
            output = choice.path("message").path("content").asText();
        }
        if (output.isBlank()) throw invalid(result.requestId(), "模型没有返回可用的结构化正文");
        LlmGateway.ProviderUsage metrics=usage(result.json(),finishReason);T value;
        try{value=structuredJson.parse(output, schema, responseType, result.requestId());}
        catch(ProviderException error){throw error.withProviderDiagnostics(finishReason,metrics.promptTokens(),metrics.completionTokens(),metrics.totalTokens());}
        return new StructuredResult<>(value, model, result.requestId(), output, false,metrics);
    }
    private ProviderException invalid(String requestId, String message) {
        return new ProviderException("INVALID_STRUCTURED_OUTPUT", message, requestId, 200, false, false);
    }
    private boolean truncated(String reason) {
        return reason != null && Set.of("length", "max_tokens", "max_output_tokens", "incomplete").contains(reason.toLowerCase(java.util.Locale.ROOT));
    }
    private ProviderException truncated(ArkHttpClient.Response result,String reason) {
        JsonNode usage=result.json().path("usage");
        long input=usage.path("input_tokens").asLong(usage.path("prompt_tokens").asLong(-1));
        long output=usage.path("output_tokens").asLong(usage.path("completion_tokens").asLong(-1));
        String message="模型输出被截断，未保存不完整结果；finish_reason="+reason
                +"，input_tokens="+(input<0?"unknown":input)+"，output_tokens="+(output<0?"unknown":output)
                +"，configured_output_limit="+properties.getMaxOutputTokens()+"；请缩小当前生成单元后续跑";
        LlmGateway.ProviderUsage metrics=usage(result.json(),reason);
        return new ProviderException("OUTPUT_TRUNCATED",message,result.requestId(),200,false,false)
                .withRawOutput(boundedRaw(result.json().toString()))
                .withProviderDiagnostics(reason,metrics.promptTokens(),metrics.completionTokens(),metrics.totalTokens());
    }
    private LlmGateway.ProviderUsage usage(JsonNode json,String finishReason){
        JsonNode usage=json.path("usage");long prompt=usage.path("input_tokens").asLong(usage.path("prompt_tokens").asLong(-1));long completion=usage.path("output_tokens").asLong(usage.path("completion_tokens").asLong(-1));long total=usage.path("total_tokens").asLong(prompt>=0&&completion>=0?prompt+completion:-1);return new LlmGateway.ProviderUsage(finishReason,prompt,completion,total);
    }
    private String boundedRaw(String raw){int limit=65536;if(raw==null||raw.length()<=limit)return raw;int half=(limit-80)/2;return raw.substring(0,half)+"\n... [provider response truncated locally] ...\n"+raw.substring(raw.length()-half);}
}
