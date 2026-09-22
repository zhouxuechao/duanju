package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;

/** Estimates editorial duration from dramatic purpose; media limits belong to the provider adapter. */
public final class DirectorDurationPolicy {
    public record DurationPlan(double editDuration,String rationale){}
    public DurationPlan recommend(JsonNode shot,JsonNode beat,JsonNode directorPlan){
        String intent=shot.path("directorIntent").asText();double average=directorPlan.path("styleProfile").path("averageShotLength").asDouble(3);
        double authored=shot.path("duration").asDouble(0);
        if(Double.isFinite(authored)&&authored>0)return new DurationPlan(authored,"authored shot duration; intent="+intent);
        double purpose=switch(intent){case "SHOW_REACTION"->1.8;case "REVEAL_INFORMATION","PAYOFF"->2.8;case "ESTABLISH_SPACE"->3.2;case "EMPHASIZE_EMOTION"->3.0;default->2.2;};
        double dialogue=0;int chars=0;for(JsonNode line:shot.path("dialogues")){String value=line.path("semanticText").asText();chars+=value.codePointCount(0,value.length());dialogue=Math.max(dialogue,line.path("endMs").asDouble()/1000d);}
        dialogue=Math.max(dialogue,chars==0?0:chars/4.5+0.35);
        int actions=shot.path("performancePlan").path("actionUnits").asInt(1);double action=0.8+Math.max(0,actions-1)*1.2;
        double base=Math.max(Math.max(purpose,dialogue),Math.max(action,average*.65));
        if("CLIMAX".equals(beat.path("importance").asText())&&dialogue==0)base+=0.25;
        double pacing=switch(directorPlan.path("scenePacing").asText()){case "FAST","CLIMAX"->0.8;case "SLOW"->1.2;default->1.0;};
        double edit=round(Math.max(EditorialTiming.MIN_SHOT_SECONDS,Math.max(dialogue,base*pacing)));
        return new DurationPlan(edit,"intent="+intent+", pacing="+directorPlan.path("scenePacing").asText()+", dialogueSeconds="+round(dialogue)+", actionUnits="+actions);
    }
    private static double round(double value){return Math.round(value*100d)/100d;}
}
