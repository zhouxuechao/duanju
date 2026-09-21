package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Enforces that director shots faithfully cover the accepted script beats. */
public final class ScriptBeatCoverageValidator {
    public List<ProductionModels.Risk> validate(JsonNode scriptBeats, JsonNode shots) {
        List<ProductionModels.Risk> risks=new ArrayList<>();
        Map<String,Integer> order=new LinkedHashMap<>();Map<String,JsonNode> definitions=new LinkedHashMap<>();int i=0;
        for(JsonNode beat:scriptBeats){String id=text(beat,"beatId");if(id.isBlank()||definitions.putIfAbsent(id,beat)!=null)error(risks,"DUPLICATE_SCRIPT_BEAT","scriptBeats["+i+"]","剧本节拍 ID 为空或重复");else order.put(id,i);i++;}
        Set<String> covered=new LinkedHashSet<>(),coveredDialogue=new LinkedHashSet<>();int previousOrder=-1,shotNo=0;
        for(JsonNode shot:shots){JsonNode covers=shot.path("coversBeats");if(!covers.isArray()){String legacy=text(shot,"beatId");if(!legacy.isBlank())covered.add(legacy);}
            for(JsonNode value:covers){String id=value.asText();Integer position=order.get(id);if(position==null)error(risks,"DIRECTOR_ADDED_BEAT","shots["+shotNo+"].coversBeats","导演镜头引用了剧本中不存在的节拍 "+id);else{if(position<previousOrder)error(risks,"BEAT_ORDER_CHANGED","shots["+shotNo+"].coversBeats","导演改变了剧本节拍顺序");previousOrder=Math.max(previousOrder,position);covered.add(id);}}
            if(!shot.path("addedStoryFacts").isEmpty()||!shot.path("addedPlot").asText("").isBlank())error(risks,"DIRECTOR_ADDED_PLOT","shots["+shotNo+"]","导演规划新增了剧本未授权的剧情事实");
            shot.path("dialogueIds").forEach(v->coveredDialogue.add(v.asText()));shotNo++;}
        for(var entry:definitions.entrySet()){
            JsonNode beat=entry.getValue();if(beat.path("required").asBoolean(true)&&!covered.contains(entry.getKey()))error(risks,"REQUIRED_BEAT_MISSING","scriptBeats."+entry.getKey(),"关键剧本节拍没有任何镜头承载");
            for(JsonNode dialogue:beat.path("dialogueIds"))if(!coveredDialogue.contains(dialogue.asText()))error(risks,"DIALOGUE_COVERAGE_MISSING","scriptBeats."+entry.getKey()+".dialogueIds","剧本对白没有进入任何镜头");
        }
        return List.copyOf(risks);
    }
    private static String text(JsonNode node,String field){return node.path(field).asText("").trim();}
    private static void error(List<ProductionModels.Risk> risks,String code,String path,String message){risks.add(new ProductionModels.Risk(code,"ERROR",path,message));}
}
