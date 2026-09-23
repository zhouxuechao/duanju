package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** Cross-shot and provider boundary facts evaluated without a model call. */
@Component
public final class ProductionBoundaryDeterministicRule implements DeterministicRule {
    private final ContinuityCompatibilityEvaluator continuity;
    public ProductionBoundaryDeterministicRule(ContinuityCompatibilityEvaluator continuity){this.continuity=continuity;}
    @Override public String id(){return "PRODUCTION_BOUNDARY";}
    @Override public List<ProductionModels.Risk> evaluate(JsonNode generationContext){
        List<ProductionModels.Risk> risks=new ArrayList<>();JsonNode durations=generationContext.path("providerCapabilities").path("supportedDurations");
        if(durations.isArray()&&!durations.isEmpty()){
            double maximum=0;for(JsonNode duration:durations)maximum=Math.max(maximum,duration.asDouble());
            double requested=generationContext.path("shot").path("duration").asDouble();
            if(requested>maximum)risks.add(error("PROVIDER_DURATION_EXCEEDED","shot.duration","镜头时长 "+requested+" 秒超过当前模型上限 "+maximum+" 秒"));
        }
        JsonNode shot=generationContext.path("shot"),previous=generationContext.path("previousTake");
        if("VIDEO".equals(generationContext.path("providerMediaType").asText())&&"CONTINUOUS".equals(shot.path("relationToPrevious").asText())){
            risks.addAll(continuity.compareObserved(previous,shot.path("startState")));
        }
        JsonNode before=generationContext.path("previousShot").path("blocking"),after=shot.path("blocking");
        if(before.isObject()&&after.isObject()&&!Set.of("LOCATION_CHANGE","TIME_JUMP").contains(shot.path("relationToPrevious").asText())){
            risks.addAll(continuity.compareBlocking(before,after));
        }
        return List.copyOf(risks);
    }
    private String text(JsonNode value,String field){return value.path(field).asText("").trim();}
    private ProductionModels.Risk error(String code,String path,String message){return new ProductionModels.Risk(code,"ERROR",path,message);}
}
