package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** Cross-shot and provider boundary facts evaluated without a model call. */
@Component
public final class ProductionBoundaryDeterministicRule implements DeterministicRule {
    private final CrossShotQc crossShot=new CrossShotQc();
    @Override public String id(){return "PRODUCTION_BOUNDARY";}
    @Override public List<ProductionModels.Risk> evaluate(JsonNode generationContext){
        List<ProductionModels.Risk> risks=new ArrayList<>();JsonNode durations=generationContext.path("providerCapabilities").path("supportedDurations");
        if(durations.isArray()&&!durations.isEmpty()){
            double maximum=0;for(JsonNode duration:durations)maximum=Math.max(maximum,duration.asDouble());
            double requested=generationContext.path("shot").path("duration").asDouble();
            if(requested>maximum)risks.add(error("PROVIDER_DURATION_EXCEEDED","shot.duration","镜头时长 "+requested+" 秒超过当前模型上限 "+maximum+" 秒"));
        }
        JsonNode shot=generationContext.path("shot"),previous=generationContext.path("previousTake");
        if("CONTINUOUS".equals(shot.path("relationToPrevious").asText())){
            if(!previous.path("selected").asBoolean()||!previous.path("locked").asBoolean()||!previous.path("qcPassed").asBoolean())
                risks.add(error("PREVIOUS_TAKE_NOT_ACCEPTED","previousTake","连续镜头的上一条视频必须已选择、锁定并通过 QC"));
            if(previous.path("observedState").isObject()&&shot.path("startState").isObject())risks.addAll(crossShot.compare(previous.path("observedState"),shot.path("startState")));
        }
        JsonNode before=generationContext.path("previousShot").path("blocking"),after=shot.path("blocking");
        if(before.isObject()&&after.isObject()&&!Set.of("LOCATION_CHANGE","TIME_JUMP").contains(shot.path("relationToPrevious").asText())){
            String beforeAxis=text(before,"axis"),afterAxis=text(after,"axis"),beforeSide=text(before,"axisSide"),afterSide=text(after,"axisSide");
            if(!beforeAxis.isBlank()&&!afterAxis.isBlank()&&!beforeAxis.equals(afterAxis))risks.add(error("AXIS_DISCONTINUITY","shot.blocking.axis","同一空间内不能改写表演轴线"));
            if(!beforeSide.isBlank()&&!afterSide.isBlank()&&!beforeSide.equals(afterSide)&&!"ON_AXIS".equals(beforeSide)&&!"ON_AXIS".equals(afterSide)&&text(after,"axisChangeReason").isBlank())
                risks.add(error("AXIS_SIDE_DISCONTINUITY","shot.blocking.axisSide","跨越 180 度轴线必须记录明确理由"));
            if("CONTINUOUS".equals(shot.path("relationToPrevious").asText()))compareScreenDirections(before.path("characters"),after.path("characters"),risks);
        }
        return List.copyOf(risks);
    }
    private void compareScreenDirections(JsonNode before,JsonNode after,List<ProductionModels.Risk> risks){
        Map<String,String> prior=new HashMap<>();for(JsonNode actor:before)prior.put(text(actor,"characterId"),text(actor,"screenDirection"));
        for(JsonNode actor:after){String id=text(actor,"characterId"),old=prior.get(id),now=text(actor,"screenDirection");if(opposite(old,now))risks.add(error("SCREEN_DIRECTION_DISCONTINUITY","shot.blocking.characters."+id+".screenDirection","连续动作的画面运动方向不能无理由反转"));}
    }
    private boolean opposite(String left,String right){return ("FRAME_LEFT".equals(left)&&"FRAME_RIGHT".equals(right))||("FRAME_RIGHT".equals(left)&&"FRAME_LEFT".equals(right))||("INTO_DEPTH".equals(left)&&"OUT_OF_DEPTH".equals(right))||("OUT_OF_DEPTH".equals(left)&&"INTO_DEPTH".equals(right));}
    private String text(JsonNode value,String field){return value.path(field).asText("").trim();}
    private ProductionModels.Risk error(String code,String path,String message){return new ProductionModels.Risk(code,"ERROR",path,message);}
}
