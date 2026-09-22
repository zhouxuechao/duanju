package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.yourapp.drama.production.ProductionModels.Risk;

/** Deterministic coverage rule for story events whose meaning may belong on the listener. */
public final class ReactionShotRule {
    private static final Set<String> SENSITIVE_EVENTS=Set.of("REVEAL","INSULT","CONFESSION","THREAT","DEATH","SECRET");
    private static final Set<String> PRIORITIES=Set.of("NONE","SPEAKER","LISTENER","EQUAL");

    public List<Risk> validate(JsonNode beat,JsonNode shots){
        List<Risk> risks=new ArrayList<>();String event=text(beat,"eventType");
        if(!SENSITIVE_EVENTS.contains(event)||beat.path("activeCharacters").size()<2)return List.of();
        String beatId=text(beat,"beatId"),priority=text(beat,"reactionPriority"),reason=text(beat,"reactionReason");
        if(!PRIORITIES.contains(priority)){error(risks,"REACTION_DECISION_REQUIRED",beatId,"敏感剧情事件必须明确说话者、听者或同等反应优先级");return List.copyOf(risks);}
        if(reason.isBlank())error(risks,"REACTION_REASON_REQUIRED",beatId,"反应镜头取舍必须说明叙事依据");
        if(!Set.of("LISTENER","EQUAL").contains(priority))return List.copyOf(risks);
        String subject=text(beat,"reactionSubjectId");Set<String> active=new LinkedHashSet<>();beat.path("activeCharacters").forEach(v->active.add(v.asText()));
        if(subject.isBlank()||!active.contains(subject)){error(risks,"REACTION_SUBJECT_INVALID",beatId,"听者反应主体必须是本节拍在场人物");return List.copyOf(risks);}
        boolean covered=false;
        for(JsonNode shot:shots)if(beatId.equals(text(shot,"beatId"))&&"SHOW_REACTION".equals(text(shot,"directorIntent"))
            &&subject.equals(text(shot,"subject"))&&!subject.equals(text(shot,"dialogueOwner"))){covered=true;break;}
        if(!covered)error(risks,"REACTION_COVERAGE_MISSING",beatId,"听者优先事件需要以该听者为主体的独立反应镜头");
        return List.copyOf(risks);
    }
    private static String text(JsonNode node,String field){return node.path(field).asText("").trim();}
    private static void error(List<Risk> risks,String code,String beatId,String message){risks.add(new Risk(code,"ERROR","dramaticBeats."+beatId,message));}
}
