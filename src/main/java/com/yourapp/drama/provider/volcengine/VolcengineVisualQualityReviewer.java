package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.production.*;
import com.yourapp.drama.provider.StructuredJson;
import java.util.*;

/** Doubao multimodal adapter. Provider-specific payloads stay outside the quality domain. */
public final class VolcengineVisualQualityReviewer implements VisualQualityReviewer {
    private final ArkHttpClient http;private final VolcengineProperties properties;private final StructuredJson structured;private final ObjectMapper mapper;private final VisualQualityProtocol protocol;
    public VolcengineVisualQualityReviewer(ArkHttpClient http,VolcengineProperties properties,StructuredJson structured,ObjectMapper mapper,VisualQualityProtocol protocol){
        properties.validateVlm();this.http=http;this.properties=properties;this.structured=structured;this.mapper=mapper;this.protocol=protocol;
    }

    @Override public JsonNode review(JsonNode expected,JsonNode generatedImage){
        String generated=media(generatedImage,"providerUrl","imageUrl","dataUrl");
        List<Map<String,Object>> content=new ArrayList<>();
        content.add(Map.of("type","input_text","text",protocol.reviewInstructions(expected,false)));
        addImage(content,"GENERATED_IMAGE",generated);
        int count=0;for(JsonNode reference:expected.path("referenceImages")){
            if(++count>12)throw ProviderException.invalid("TOO_MANY_VISUAL_REFERENCES","视觉质检参考图最多 12 张");
            String label="REFERENCE "+reference.path("role").asText("UNKNOWN")+" assetId="+reference.path("assetId").asText();
            addImage(content,label,media(reference,"url","providerUrl","dataUrl"));
        }
        JsonNode previous=expected.path("approvedPreviousKeyframe");if(previous.isObject()&&!previous.isEmpty())addImage(content,"PREVIOUS_APPROVED_KEYFRAME",media(previous,"url","providerUrl","dataUrl"));
        JsonNode schema=structured.schema(protocol.schema(expected));
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",properties.getVlmModel());body.put("stream",false);body.put("store",false);body.put("thinking",Map.of("type","disabled"));body.put("max_output_tokens",6000);
        body.put("input",List.of(Map.of("role","user","content",content)));body.put("text",Map.of("format",Map.of("type","json_schema","name","visual_quality_review","strict",true,"schema",schema)));
        ArkHttpClient.Response response=http.exchange("POST","/responses",body,true);String output=output(response);
        JsonNode parsed=structured.parse(output,schema,JsonNode.class,response.requestId());
        try{protocol.validate(parsed,expected);}catch(IllegalArgumentException error){throw new ProviderException("INVALID_STRUCTURED_OUTPUT",error.getMessage(),response.requestId(),200,false,false);}
        ObjectNode result=(ObjectNode)parsed;result.putObject("_provider").put("name","VOLCENGINE").put("model",properties.getVlmModel()).put("requestId",response.requestId()==null?"":response.requestId());return result;
    }

    private void addImage(List<Map<String,Object>> content,String label,String url){ProviderInputs.imageReference(url);content.add(Map.of("type","input_text","text",label));content.add(Map.of("type","input_image","image_url",url,"detail","high"));}
    private String media(JsonNode source,String...names){for(String name:names){String value=source.path(name).asText();if(!value.isBlank())return value;}throw ProviderException.invalid("VISUAL_MEDIA_REQUIRED","视觉质检缺少可读取的图片");}
    private String output(ArkHttpClient.Response response){
        if(!"completed".equals(response.json().path("status").asText()))throw new ProviderException("VLM_INCOMPLETE","视觉模型输出未完成",response.requestId(),200,false,false);
        StringBuilder value=new StringBuilder();for(JsonNode item:response.json().path("output"))for(JsonNode part:item.path("content"))if("output_text".equals(part.path("type").asText()))value.append(part.path("text").asText());
        if(value.isEmpty())throw new ProviderException("INVALID_STRUCTURED_OUTPUT","视觉模型没有返回结构化结果",response.requestId(),200,false,false);return value.toString();
    }
}
