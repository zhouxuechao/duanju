package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.yourapp.drama.production.ProductionModels.Risk;

/** Scene-level directing checks: rhythm, coverage and emotional continuity. */
public final class DirectorPlanValidator {
    private final ReactionShotRule reactionShotRule=new ReactionShotRule();
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
        String previousSemantic="";int shotIndex=0;
        for(JsonNode shot:shots){
            String path="shots["+shotIndex+"]",purpose=first(text(shot,"shotPurpose"),text(shot,"purpose"));
            String visual=first(text(shot,"visualInformation"),text(shot,"visualFocus")),action=text(shot,"action"),subject=text(shot,"subject");
            boolean semanticContract=shot.has("shotPurpose")||shot.has("purpose")||shot.has("visualInformation")||shot.has("visualFocus")||shot.has("action")||shot.has("startState")||shot.has("endState");
            if(semanticContract&&purpose.isBlank())error(risks,"SHOT_NO_PURPOSE",path+".shotPurpose","镜头必须说明观众为何需要看到它");
            if(semanticContract&&visual.isBlank()&&action.isBlank()&&subject.isBlank())error(risks,"NO_VISUAL_INFORMATION",path+".visualInformation","镜头必须提供可见主体、动作或新增视觉信息");
            JsonNode beat=beats.get(text(shot,"beatId"));
            boolean hasSnapshots=shot.path("startState").isObject()&&shot.path("endState").isObject();
            boolean stateChanged=hasSnapshots&&!shot.path("startState").equals(shot.path("endState"));
            boolean informationChanged=beat!=null&&(!text(beat,"informationReveal").isBlank()||!beat.path("storyFactChanges").isEmpty()||!beat.path("relationshipChanges").isEmpty()||!beat.path("knowledgeChange").path("audienceLearns").isEmpty()||!beat.path("knowledgeChange").path("characterChanges").isEmpty());
            boolean emotionChanged=beat!=null&&!text(beat,"emotionBefore").isBlank()&&!text(beat,"emotionBefore").equals(text(beat,"emotionAfter"));
            boolean removable=false;
            if(semanticContract&&hasSnapshots&&!stateChanged&&!informationChanged&&!emotionChanged){error(risks,"NO_STATE_CHANGE",path+".endState","镜头没有改变状态、信息或情绪");removable=true;}
            String semantic=String.join("|",text(shot,"beatId"),purpose,visual,action,subject,text(shot,"directorIntent"),shot.path("audienceLearn").toString(),shot.path("emotionChange").toString(),shot.path("endState").toString());
            boolean comparable=semanticContract&&!visual.isBlank()&&!action.isBlank();
            if(comparable&&repetitionReason.isBlank()&&!previousSemantic.isBlank()&&previousSemantic.equals(semantic)){error(risks,"DUPLICATE_INFORMATION",path,"相邻镜头重复相同剧情信息、动作和视觉内容");removable=true;}
            if(removable)error(risks,"SHOT_CAN_BE_REMOVED",path,"删除本镜不会损失已声明的剧情信息或状态变化");
            previousSemantic=comparable?semantic:"";shotIndex++;
        }
        double reactionPreference=director.path("styleProfile").path("reactionShotPreference").asDouble();
        for(JsonNode beat:beats.values()){
            risks.addAll(reactionShotRule.validate(beat,shots));
            if(beat.path("activeCharacters").size()>1&&!text(beat,"informationReveal").isBlank()
            &&Set.of("HIGH","CLIMAX").contains(text(beat,"importance"))&&reactionPreference>=0.4){
            boolean reaction=false,dedicatedPayoffReaction=false;int beatShots=0;for(JsonNode shot:shots)if(text(shot,"beatId").equals(text(beat,"beatId"))){beatShots++;if("SHOW_REACTION".equals(text(shot,"directorIntent")))reaction=true;if("EMPHASIZE_EMOTION".equals(text(shot,"directorIntent"))&&("REACTION".equals(text(shot,"relationToPrevious"))||shot.path("characterIds").size()>1)){reaction=true;dedicatedPayoffReaction=true;}}
            if(!reaction||beatShots<2&&!dedicatedPayoffReaction)error(risks,"REACTION_COVERAGE_MISSING","dramaticBeats."+text(beat,"beatId"),"重要信息揭示需要独立的听者反应镜头");
            }
        }
        return List.copyOf(risks);
    }
    private static String text(JsonNode n,String f){return n.path(f).asText("").trim();}
    private static String first(String first,String second){return first.isBlank()?second:first;}
    private static boolean sharesActiveCharacter(JsonNode first,JsonNode second){
        Set<String> active=new HashSet<>();first.path("activeCharacters").forEach(value->active.add(value.asText()));
        for(JsonNode value:second.path("activeCharacters"))if(active.contains(value.asText()))return true;
        return false;
    }
    private static void error(List<Risk> risks,String code,String path,String message){risks.add(new Risk(code,"ERROR",path,message));}
}
