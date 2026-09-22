package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class StoryQualitySchemas {
    private StoryQualitySchemas() {}
    private static ObjectNode text(){return Documents.obj().put("type","string");}
    private static ObjectNode bool(){return Documents.obj().put("type","boolean");}
    private static ObjectNode score(){return Documents.obj().put("type","integer").put("minimum",0).put("maximum",100);}
    private static ObjectNode values(String... values){ObjectNode schema=text();ArrayNode choices=schema.putArray("enum");for(String value:values)choices.add(value);return schema;}
    private static ObjectNode array(JsonNode item){return (ObjectNode)Documents.obj().put("type","array").put("maxItems",20).set("items",item);}
    private static ObjectNode object(Object... pairs){ObjectNode s=Documents.obj().put("type","object").put("additionalProperties",false),p=s.putObject("properties");ArrayNode r=s.putArray("required");for(int i=0;i<pairs.length;i+=2){p.set((String)pairs[i],(JsonNode)pairs[i+1]);r.add((String)pairs[i]);}return s;}
    static ObjectNode schema(){
        ObjectNode dimensions=object("hook",score(),"progression",score(),"conflict",score(),"characterConsistency",score(),"genreFit",score(),"continuity",score(),"payoff",score(),"cliffhanger",score(),"narrativeNecessity",score(),"dialogueNaturalness",score());
        ObjectNode delta=object("factsChanged",array(text()),"relationshipsChanged",array(text()),"goalsChanged",array(text()),"knowledgeChanged",array(text()),"riskChanged",array(text()),"resourcesChanged",array(text()));
        ObjectNode issue=object("code",text(),"severity",values("INFO","WARNING","BLOCKER"),
            "category",values("HOOK","CONFLICT","MOTIVATION","INFORMATION_GAP","EMOTION_CURVE","ESCALATION","REVEAL","REVERSAL","CLIFFHANGER","PAYOFF","DIALOGUE","REDUNDANCY","SCENE_PURPOSE","CONTINUITY"),
            "message",text(),"evidence",text(),"recommendation",text(),"targetPath",text());
        return object("passed",bool(),"dimensions",dimensions,"episodeDelta",delta,"watchReason",text(),"nextEpisodeReason",text(),"blockingIssues",array(text()),"issues",array(issue),"rewriteInstructions",array(text()),"rewriteRequired",bool());
    }
}
