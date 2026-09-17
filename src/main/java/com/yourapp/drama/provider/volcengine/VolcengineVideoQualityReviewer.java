package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.production.*;
import com.yourapp.drama.provider.StructuredJson;
import java.util.*;

/** Reviews temporal continuity from five ordered frames sampled from one generated take. */
public final class VolcengineVideoQualityReviewer implements VideoQualityReviewer {
    private final ArkHttpClient http;private final VolcengineProperties properties;private final StructuredJson structured;private final VisualQualityProtocol protocol;
    public VolcengineVideoQualityReviewer(ArkHttpClient http,VolcengineProperties properties,StructuredJson structured,VisualQualityProtocol protocol){properties.validateVlm();this.http=http;this.properties=properties;this.structured=structured;this.protocol=protocol;}

    @Override public JsonNode review(JsonNode expected,List<Frame> frames){
        if(frames==null||frames.size()!=5)throw ProviderException.invalid("VIDEO_SAMPLE_COUNT","视频质检必须提供起点、25%、50%、75% 和终点五帧");
        List<Map<String,Object>> content=new ArrayList<>();content.add(Map.of("type","input_text","text",protocol.reviewInstructions(expected,true)));
        for(int i=0;i<frames.size();i++){Frame frame=frames.get(i);ProviderInputs.imageReference(frame.imageDataUrl());content.add(Map.of("type","input_text","text",String.format(Locale.ROOT,"VIDEO_FRAME_%d t=%.3fs",i+1,frame.timestampSeconds())));content.add(Map.of("type","input_image","image_url",frame.imageDataUrl(),"detail","high"));}
        for(JsonNode reference:expected.path("referenceImages")){String url=reference.path("url").asText();ProviderInputs.imageReference(url);content.add(Map.of("type","input_text","text","REFERENCE "+reference.path("role").asText()+" assetId="+reference.path("assetId").asText()));content.add(Map.of("type","input_image","image_url",url,"detail","high"));}
        JsonNode schema=structured.schema(protocol.schema(expected));Map<String,Object> body=new LinkedHashMap<>();body.put("model",properties.getVlmModel());body.put("stream",false);body.put("store",false);body.put("thinking",Map.of("type","disabled"));body.put("max_output_tokens",6000);body.put("input",List.of(Map.of("role","user","content",content)));body.put("text",Map.of("format",Map.of("type","json_schema","name","video_quality_review","strict",true,"schema",schema)));
        ArkHttpClient.Response response=http.exchange("POST","/responses",body,true);JsonNode parsed=structured.parse(output(response),schema,JsonNode.class,response.requestId());
        try{protocol.validate(parsed,expected);}catch(IllegalArgumentException error){throw new ProviderException("INVALID_STRUCTURED_OUTPUT",error.getMessage(),response.requestId(),200,false,false);}
        ObjectNode result=(ObjectNode)parsed;result.putObject("_provider").put("name","VOLCENGINE").put("model",properties.getVlmModel()).put("requestId",response.requestId()==null?"":response.requestId());return result;
    }
    private String output(ArkHttpClient.Response response){if(!"completed".equals(response.json().path("status").asText()))throw new ProviderException("VLM_INCOMPLETE","视频视觉模型输出未完成",response.requestId(),200,false,false);StringBuilder value=new StringBuilder();for(JsonNode item:response.json().path("output"))for(JsonNode part:item.path("content"))if("output_text".equals(part.path("type").asText()))value.append(part.path("text").asText());if(value.isEmpty())throw new ProviderException("INVALID_STRUCTURED_OUTPUT","视频视觉模型没有返回结构化结果",response.requestId(),200,false,false);return value.toString();}
}
