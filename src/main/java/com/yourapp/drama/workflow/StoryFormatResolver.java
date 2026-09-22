package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.StoryFormat;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Separates delivery form and visual presentation from duration and story genre. */
@Component
public final class StoryFormatResolver {
    public ObjectNode resolve(JsonNode project){
        JsonNode explicit=project.path("storyFormat");int seconds=Math.max(1,project.path("targetDuration").asInt(20));
        String narrative=code(explicit.path("narrativeForm").asText(inferNarrativeForm(seconds,project.path("episodeCount").asInt(1))));
        String presentation=code(explicit.path("presentation").asText("LIVE_ACTION"));
        String orientation=code(explicit.path("orientation").asText(orientation(project.path("ratio").asText("9:16"))));
        String formatId=code(explicit.path("formatId").asText(narrative));StoryFormat format=new StoryFormat(formatId,narrative,presentation,orientation);
        ObjectNode result=Documents.obj().put("formatId",format.formatId()).put("narrativeForm",format.narrativeForm())
            .put("presentation",format.presentation()).put("orientation",format.orientation());
        result.set("visualStylePolicy",Documents.obj().put("source","STORY_BIBLE").put("presentation",format.presentation())
            .put("orientation",format.orientation()).put("allowNonHumanCharacters",true));
        return result.put("fingerprint",sha256(result.toString()));
    }
    private String inferNarrativeForm(int seconds,int episodes){
        if(seconds<=45)return "MICRO_DRAMA";
        if(episodes<=1&&seconds>=600)return "FILM";
        if(seconds<=300)return "SHORT_DRAMA";
        return "SERIES";
    }
    private String orientation(String ratio){return switch(ratio.trim()){case "9:16","3:4"->"VERTICAL";case "1:1"->"SQUARE";default->"HORIZONTAL";};}
    private String code(String value){String normalized=value==null?"":value.trim().toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');if(normalized.isBlank()||!normalized.matches("[A-Z0-9_]+"))throw new IllegalArgumentException("storyFormat 值无效："+value);return normalized;}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
}
