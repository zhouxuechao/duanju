package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Final scene-level review after shot approval, before timeline composition. */
public final class DirectorSceneReview {
    private final ObjectMapper mapper;private final DirectorPlanValidator validator=new DirectorPlanValidator();
    public DirectorSceneReview(ObjectMapper mapper){this.mapper=mapper;}
    public ObjectNode review(JsonNode scenePlan){
        LinkedHashSet<String> codes=new LinkedHashSet<>();for(ProductionModels.Risk risk:validator.validate(scenePlan))codes.add(risk.code());
        Set<String> sizes=new HashSet<>(),durations=new HashSet<>();int reaction=0,spatialBreaks=0,duplicateRun=0,maxDuplicateRun=0;String lastSignature="",lastAxis="",lastSide="";
        for(JsonNode shot:scenePlan.path("shots")){
            sizes.add(text(shot,"shotSize"));durations.add(String.format(Locale.ROOT,"%.2f",shot.path("editDuration").asDouble(shot.path("duration").asDouble())));
            if("SHOW_REACTION".equals(text(shot,"directorIntent")))reaction++;
            String axis=text(shot.path("blocking"),"axis"),side=text(shot.path("blocking"),"axisSide");if(!lastAxis.isBlank()&&!axis.equals(lastAxis))spatialBreaks++;if(!lastSide.isBlank()&&!side.equals(lastSide)&&!"ON_AXIS".equals(side)&&!"ON_AXIS".equals(lastSide))spatialBreaks++;lastAxis=axis;lastSide=side;
            String signature=text(shot,"shotSize")+"|"+text(shot,"cameraAngle")+"|"+text(shot,"cameraMovement")+"|"+text(shot,"subject");duplicateRun=signature.equals(lastSignature)?duplicateRun+1:1;maxDuplicateRun=Math.max(maxDuplicateRun,duplicateRun);lastSignature=signature;
        }
        if(spatialBreaks>0)codes.add("SPATIAL_CONTINUITY_BREAK");if(maxDuplicateRun>=4)codes.add("DUPLICATE_COMPOSITION_RUN");
        ObjectNode result=mapper.createObjectNode().put("passed",codes.isEmpty()).put("shotCount",scenePlan.path("shots").size()).put("uniqueShotSizes",sizes.size()).put("uniqueDurations",durations.size()).put("reactionShots",reaction).put("spatialContinuityBreaks",spatialBreaks).put("maximumDuplicateCompositionRun",maxDuplicateRun);ArrayNode failures=result.putArray("failureCodes");codes.forEach(failures::add);return result;
    }
    private static String text(JsonNode n,String field){return n.path(field).asText("").trim();}
}
