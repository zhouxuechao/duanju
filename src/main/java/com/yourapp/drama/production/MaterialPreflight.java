package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

/** Deterministic paid-request gate: hard limits block; recommended limits only warn. */
@Component
public class MaterialPreflight {
    public record Result(boolean passed,List<String> blockingCodes,List<String> warnings,List<ObjectNode> requiredKeep,List<ObjectNode> suggestedExclude){
        public Result{blockingCodes=List.copyOf(blockingCodes);warnings=List.copyOf(warnings);requiredKeep=List.copyOf(requiredKeep);suggestedExclude=List.copyOf(suggestedExclude);}
    }
    public Result validate(JsonNode refs,VideoModelProfile profile){
        List<String> blocks=new ArrayList<>(),warnings=new ArrayList<>();List<ObjectNode> required=new ArrayList<>(),optional=new ArrayList<>();
        int images=0,videos=0,audios=0,total=0;double videoSeconds=0,audioSeconds=0;
        if(refs!=null&&refs.isArray())for(JsonNode ref:refs){total++;if(ref.isObject()&&ref.path("required").asBoolean(false))required.add((ObjectNode)ref);else if(ref.isObject())optional.add((ObjectNode)ref);
            String type=ref.path("mediaType").asText(ref.path("type").asText(""));switch(type){case "image_url"->{images++;if(Math.max(ref.path("width").asInt(0),ref.path("height").asInt(0))>4096)blocks.add("REFERENCE_IMAGE_RESOLUTION_EXCEEDED");}case "video_url"->{videos++;videoSeconds+=Math.max(0,ref.path("durationSeconds").asDouble(0));}case "audio_url"->{audios++;audioSeconds+=Math.max(0,ref.path("durationSeconds").asDouble(0));}default->blocks.add("REFERENCE_MEDIA_TYPE_UNSUPPORTED");}
            if(!validUrl(ref.path("url").asText("")))blocks.add("REFERENCE_URL_INVALID");
            if(ref.has("readable")&&!ref.path("readable").asBoolean())blocks.add("REFERENCE_MEDIA_UNREADABLE");
            String mime=ref.path("mimeType").asText("");if(!mime.isBlank()&&!mimeMatches(type,mime))blocks.add("REFERENCE_MIME_TYPE_MISMATCH");
            String role=ref.path("role").asText("");if(role.isBlank())blocks.add("REFERENCE_ROLE_MISSING");else if(!compatible(type,role))blocks.add("REFERENCE_ROLE_MEDIA_MISMATCH");
        }
        compare(images,profile.hardLimits().images(),"HARD_IMAGE_REFERENCE_LIMIT_EXCEEDED",blocks);
        compare(videos,profile.hardLimits().videos(),"HARD_VIDEO_REFERENCE_LIMIT_EXCEEDED",blocks);
        compare(audios,profile.hardLimits().audios(),"HARD_AUDIO_REFERENCE_LIMIT_EXCEEDED",blocks);
        compare(total,profile.hardLimits().total(),"HARD_TOTAL_REFERENCE_LIMIT_EXCEEDED",blocks);
        if(videoSeconds>profile.maxReferenceVideoSeconds())blocks.add("HARD_VIDEO_DURATION_LIMIT_EXCEEDED");
        if(audioSeconds>profile.maxReferenceAudioSeconds())blocks.add("HARD_AUDIO_DURATION_LIMIT_EXCEEDED");
        compare(images,profile.recommendedLimits().images(),"RECOMMENDED_IMAGE_REFERENCE_LIMIT_EXCEEDED",warnings);
        compare(videos,profile.recommendedLimits().videos(),"RECOMMENDED_VIDEO_REFERENCE_LIMIT_EXCEEDED",warnings);
        compare(audios,profile.recommendedLimits().audios(),"RECOMMENDED_AUDIO_REFERENCE_LIMIT_EXCEEDED",warnings);
        compare(total,profile.recommendedLimits().total(),"RECOMMENDED_TOTAL_REFERENCE_LIMIT_EXCEEDED",warnings);
        return new Result(blocks.isEmpty(),distinct(blocks),distinct(warnings),required,optional);
    }
    private void compare(int value,int limit,String code,List<String> out){if(value>limit)out.add(code);}
    private boolean validUrl(String raw){try{URI uri=URI.create(raw);return Set.of("https","http","data").contains(uri.getScheme());}catch(Exception ignored){return false;}}
    private boolean mimeMatches(String type,String mime){return switch(type){case "image_url"->mime.startsWith("image/");case "video_url"->mime.startsWith("video/");case "audio_url"->mime.startsWith("audio/");default->false;};}
    private boolean compatible(String type,String role){if(Set.of("VOICE_IDENTITY","AUDIO_REFERENCE","MUSIC_STYLE","RHYTHM_REFERENCE","SFX_REFERENCE").contains(role))return "audio_url".equals(type);if("PREVIOUS_TAKE".equals(role))return "video_url".equals(type);if(Set.of("START_FRAME","END_FRAME","INTERMEDIATE_KEYFRAME","KEYFRAME_PROVIDER","CHARACTER_IDENTITY","CHARACTER_LOOK","LOCATION","LOCATION_LAYOUT","LOCATION_IDENTITY","PROP","PROP_IDENTITY","STORYBOARD","COMPOSITION_REFERENCE","WHITE_MODEL_REFERENCE").contains(role))return "image_url".equals(type);return true;}
    private List<String> distinct(List<String> input){return input.stream().distinct().toList();}
}
