package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic first-three-second gate over model-authored semantic labels and observable presentation. */
public final class HookRule {
    private static final Set<String> SIGNALS=Set.of("CONFLICT","ANOMALY","DANGER","MYSTERY","SECRET","EMOTION",
        "IDENTITY_CONTRAST","QUESTION","VISUAL_SURPRISE");

    public record Result(boolean passed,String code,List<String> evidence) {}

    public Result evaluate(JsonNode episodeScript){
        List<JsonNode> opening=new ArrayList<>();
        for(JsonNode beat:episodeScript.path("beatBoundaries"))
            if(beat.path("startSec").asDouble(Double.MAX_VALUE)<3&&beat.path("endSec").asDouble(0)>0)opening.add(beat);
        if(opening.isEmpty())return new Result(false,"HOOK_FIRST_THREE_SECONDS_EMPTY",List.of());

        LinkedHashSet<String> evidence=new LinkedHashSet<>();
        boolean semantic=false,observable=false;
        for(JsonNode beat:opening){
            for(JsonNode signal:beat.path("hookSignals"))if(SIGNALS.contains(signal.asText())){
                semantic=true;evidence.add(signal.asText());
            }
            if(hasContent(beat.path("action"))){observable=true;evidence.add("ACTION");}
            if(hasContent(beat.path("dialogue"))){observable=true;evidence.add("DIALOGUE");}
            if(hasContent(beat.path("visualInformation"))){observable=true;evidence.add("VISUAL");}
        }
        if(!semantic||!observable)return new Result(false,"HOOK_NO_OBSERVABLE_TRIGGER",List.copyOf(evidence));
        return new Result(true,"",List.copyOf(evidence));
    }

    private static boolean hasContent(JsonNode value){
        if(value.isTextual())return !value.asText().trim().isBlank();
        if(value.isArray())for(JsonNode item:value)if(hasContent(item))return true;
        if(value.isObject())return value.fields().hasNext();
        return false;
    }
}
