package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.yourapp.drama.production.ProductionModels.Risk;

/** Scene-level directing checks: rhythm, coverage and emotional continuity. */
public final class DirectorPlanValidator {
    public List<Risk> validate(JsonNode output){
        List<Risk> risks=new ArrayList<>();JsonNode director=output.path("directorPlan"),shots=output.path("shots");
        String repetitionReason=text(director,"shotRepetitionReason");
        if(shots.size()>=6&&repetitionReason.isBlank()){
            int run=1,maxRun=1;String previous="";Set<Long> durations=new HashSet<>();
            for(JsonNode shot:shots){String size=text(shot,"shotSize");run=size.equals(previous)?run+1:1;maxRun=Math.max(maxRun,run);previous=size;durations.add(Math.round(shot.path("duration").asDouble()*100));}
            if(maxRun>=6)error(risks,"MECHANICAL_SHOT_SIZE","shots","连续六个以上相同景别且没有导演理由");
            if(durations.size()==1)error(risks,"FLAT_PACING","shots","长场景镜头时长完全相同且没有节奏理由");
        }
        String activity=text(director.path("styleProfile"),"cameraActivity");double limit=switch(activity){case "LOW"->0.25;case "HIGH"->0.8;default->0.5;};
        long moving=0;for(JsonNode shot:shots)if(!"STATIC".equals(text(shot,"cameraMovement")))moving++;
        if(shots.size()>=4&&moving>(long)Math.ceil(shots.size()*limit))error(risks,"EXCESSIVE_CAMERA_ACTIVITY","shots","运镜比例与导演风格配置冲突");
        JsonNode previousBeat=null;Map<String,JsonNode> beats=new LinkedHashMap<>();
        for(JsonNode beat:output.path("dramaticBeats")){
            beats.put(text(beat,"beatId"),beat);
            if(previousBeat!=null&&sharesActiveCharacter(previousBeat,beat)&&!text(previousBeat,"emotionAfter").equals(text(beat,"emotionBefore")))error(risks,"EMOTION_ARC_GAP","dramaticBeats","相邻剧情节拍中同一人物的结束情绪与开始情绪不连续");
            previousBeat=beat;
        }
        double reactionPreference=director.path("styleProfile").path("reactionShotPreference").asDouble();
        for(JsonNode beat:beats.values())if(beat.path("activeCharacters").size()>1&&!text(beat,"informationReveal").isBlank()
            &&Set.of("HIGH","CLIMAX").contains(text(beat,"importance"))&&reactionPreference>=0.4){
            boolean reaction=false,dedicatedPayoffReaction=false;int beatShots=0;for(JsonNode shot:shots)if(text(shot,"beatId").equals(text(beat,"beatId"))){beatShots++;if("SHOW_REACTION".equals(text(shot,"directorIntent")))reaction=true;if("EMPHASIZE_EMOTION".equals(text(shot,"directorIntent"))&&("REACTION".equals(text(shot,"relationToPrevious"))||shot.path("characterIds").size()>1)){reaction=true;dedicatedPayoffReaction=true;}}
            if(!reaction||beatShots<2&&!dedicatedPayoffReaction)error(risks,"REACTION_COVERAGE_MISSING","dramaticBeats."+text(beat,"beatId"),"重要信息揭示需要独立的听者反应镜头");
        }
        return List.copyOf(risks);
    }
    private static String text(JsonNode n,String f){return n.path(f).asText("").trim();}
    private static boolean sharesActiveCharacter(JsonNode first,JsonNode second){
        Set<String> active=new HashSet<>();first.path("activeCharacters").forEach(value->active.add(value.asText()));
        for(JsonNode value:second.path("activeCharacters"))if(active.contains(value.asText()))return true;
        return false;
    }
    private static void error(List<Risk> risks,String code,String path,String message){risks.add(new Risk(code,"ERROR",path,message));}
}
