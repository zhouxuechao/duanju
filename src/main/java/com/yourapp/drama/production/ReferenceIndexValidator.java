package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates the final @image/@video/@audio mapping after all filtering. */
@Component
public class ReferenceIndexValidator {
    private static final Pattern TOKEN=Pattern.compile("@(image|video|audio)(\\d+)");
    private final ObjectMapper mapper;
    public ReferenceIndexValidator(ObjectMapper mapper){this.mapper=mapper;}
    public List<ObjectNode> map(JsonNode refs){
        Map<String,Integer> counters=new HashMap<>();List<ObjectNode> result=new ArrayList<>();
        if(refs!=null&&refs.isArray())for(JsonNode ref:refs){if(!ref.isObject())continue;ObjectNode mapped=((ObjectNode)ref).deepCopy();String media=ref.path("mediaType").asText(ref.path("type").asText(""));String prefix=switch(media){case "image_url"->"image";case "video_url"->"video";case "audio_url"->"audio";default->throw new IllegalArgumentException("REFERENCE_MEDIA_TYPE_UNSUPPORTED: "+media);};int index=counters.merge(prefix,1,Integer::sum);mapped.put("providerRef","@"+prefix+index);result.add(mapped);}return List.copyOf(result);
    }
    public void validatePrompt(String prompt,JsonNode refs){Set<String> bound=new HashSet<>();map(refs).forEach(n->bound.add(n.path("providerRef").asText()));Matcher matcher=TOKEN.matcher(prompt==null?"":prompt);while(matcher.find())if(!bound.contains(matcher.group()))throw new IllegalArgumentException("REFERENCE_INDEX_NOT_BOUND: "+matcher.group());}
}
