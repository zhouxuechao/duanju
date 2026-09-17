package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Deterministic reviewer for tests; it implements the same contract as the real VLM. */
public class FakeVisualQualityReviewer implements VisualQualityReviewer {
    private final ObjectMapper mapper;
    public FakeVisualQualityReviewer(ObjectMapper mapper){this.mapper=mapper;}

    public JsonNode review(JsonNode expected,JsonNode image){
        JsonNode required=expected.path("requiredConstraints"),observed=image.path("observedConstraints");
        ObjectNode result=mapper.createObjectNode();
        for(String metric:VisualQualityProtocol.METRICS)result.set(metric,metric(true,100,.99,"结构化约束一致"));
        ArrayNode failures=result.putArray("failureCodes");List<String> reasons=new ArrayList<>();
        if(!required.isObject()||required.isEmpty()){
            result.put("failureOriginHint","CONTEXT_RESOLUTION").put("overallScore",0).put("overallConfidence",0)
                .put("decision","MANUAL_REVIEW").put("reason","缺少可验证的预期视觉约束");result.putArray("characters");failures.add("OTHER");return result;
        }
        verify(required,observed,"characterIdentity","characterIdentity","IDENTITY_MISMATCH",result,failures,reasons);
        verify(required,observed,"clothing","clothingConsistency","CLOTHING_MISMATCH",result,failures,reasons);
        verify(required,observed,"location","locationConsistency","LOCATION_MISMATCH",result,failures,reasons);
        verify(required,observed,"prop","propConsistency","PROP_MISMATCH",result,failures,reasons);
        verify(required,observed,"props","propConsistency","PROP_MISMATCH",result,failures,reasons);
        verify(required,observed,"composition","compositionQuality","COMPOSITION_ERROR",result,failures,reasons);
        verify(required,observed,"action","actionAccuracy","ACTION_MISMATCH",result,failures,reasons);
        verify(required,observed,"style","styleConsistency","STYLE_MISMATCH",result,failures,reasons);
        if(required.path("characterIdentity").isArray()&&observed.path("characterIdentity").isArray()&&required.path("characterIdentity").size()!=observed.path("characterIdentity").size())
            fail(result,"characterCount","CHARACTER_COUNT_ERROR",failures,reasons,"人物数量与预期不一致");
        ArrayNode characters=result.putArray("characters");for(JsonNode character:required.path("characterIdentity")){
            String id=character.path("id").asText();if(id.isBlank())continue;
            ObjectNode item=characters.addObject().put("characterId",id).put("characterName",character.path("name").asText());item.set("identity",result.path("characterIdentity").deepCopy());
        }
        boolean passed=failures.isEmpty();double total=0;for(String metric:VisualQualityProtocol.METRICS)total+=result.path(metric).path("score").asDouble();
        result.put("overallScore",total/VisualQualityProtocol.METRICS.size()).put("overallConfidence",.99)
            .put("decision",passed?"PASS":"REGENERATE").put("failureOriginHint",passed?"UNKNOWN":"PROVIDER_OUTPUT")
            .put("reason",passed?"全部结构化约束一致":String.join("；",reasons));return result;
    }

    private ObjectNode metric(boolean pass,double score,double confidence,String reason){return mapper.createObjectNode().put("score",score).put("pass",pass).put("confidence",confidence).put("reason",reason);}
    private void verify(JsonNode expected,JsonNode observed,String field,String protocolMetric,String code,ObjectNode result,ArrayNode failures,List<String> reasons){
        if(!expected.has(field))return;if(!expected.path(field).equals(observed.path(field))){result.set(protocolMetric,metric(false,0,.99,field+" 与预期不一致"));add(failures,code);reasons.add(field+" 与预期不一致");}
    }
    private void fail(ObjectNode result,String metric,String code,ArrayNode failures,List<String> reasons,String reason){result.set(metric,metric(false,0,.99,reason));add(failures,code);reasons.add(reason);}
    private void add(ArrayNode values,String code){for(JsonNode value:values)if(code.equals(value.asText()))return;values.add(code);}
}
