package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Type-pack driven ending gate; the main workflow does not branch on genres. */
public final class EpisodeEndingRule {
    private static final Set<String> TYPES=Set.of("OPEN_QUESTION","UNRESOLVED_CONFLICT","REVEAL","DECISION","DANGER","REVERSAL","NATURAL_CLOSE");
    private static final Map<String,Integer> STRENGTH=Map.of("LOW",1,"MEDIUM",2,"HIGH",3);
    public record Result(boolean passed,String code,List<String> evidence){}

    public Result evaluate(JsonNode ending,JsonNode policy){
        String type=ending.path("primaryType").asText(),strength=ending.path("strength").asText();
        if(!TYPES.contains(type)||!STRENGTH.containsKey(strength))
            return new Result(false,"EPISODE_ENDING_INVALID",List.of(type,strength));
        if("NATURAL_CLOSE".equals(type))
            return new Result(false,"EPISODE_ENDING_TOO_WEAK",List.of(type,strength));
        String minimum=policy.path("minimumStrength").asText("MEDIUM");
        if(STRENGTH.get(strength)<STRENGTH.getOrDefault(minimum,2))
            return new Result(false,"EPISODE_ENDING_TOO_WEAK",List.of(type,strength,"minimum="+minimum));
        if(ending.path("unresolvedPressure").asText().isBlank()&&ending.path("nextEpisodeQuestion").asText().isBlank())
            return new Result(false,"EPISODE_ENDING_NO_FORWARD_PRESSURE",List.of(type,strength));
        return new Result(true,"",List.of(type,strength));
    }
}
