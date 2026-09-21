package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;

/** World-space anchor contract. Screen-side projection may change with camera placement. */
@Component
public class SpatialAnchor {
    private final ObjectMapper mapper;
    public SpatialAnchor(ObjectMapper mapper){this.mapper=mapper;}
    public List<ObjectNode> resolve(JsonNode previous,JsonNode current,boolean continuous){
        Map<String,ObjectNode> prior=index(previous);List<ObjectNode> result=new ArrayList<>();
        if(current!=null&&current.isArray())for(JsonNode value:current){ObjectNode anchor=validate(value);String key=key(anchor);ObjectNode old=prior.remove(key);
            if(continuous&&old!=null&&!worldRelation(old).equals(worldRelation(anchor)))throw new IllegalArgumentException("SPATIAL_ANCHOR_WORLD_RELATION_CHANGED: "+key);
            result.add(anchor);
        }
        if(continuous)result.addAll(prior.values().stream().map(ObjectNode::deepCopy).toList());
        return List.copyOf(result);
    }
    public ObjectNode validate(JsonNode value){if(value==null||!value.isObject())throw new IllegalArgumentException("SPATIAL_ANCHOR_INVALID");ObjectNode n=((ObjectNode)value).deepCopy();for(String field:List.of("subject","anchorObject","relation","facing","distance","side"))if(n.path(field).asText("").isBlank())throw new IllegalArgumentException("SPATIAL_ANCHOR_FIELD_REQUIRED: "+field);return n;}
    private Map<String,ObjectNode> index(JsonNode values){Map<String,ObjectNode> result=new LinkedHashMap<>();if(values!=null&&values.isArray())for(JsonNode value:values){ObjectNode n=validate(value);result.put(key(n),n);}return result;}
    private String key(JsonNode n){return n.path("subject").asText()+"|"+n.path("anchorObject").asText();}
    private String worldRelation(JsonNode n){return n.path("relation").asText()+"|"+n.path("facing").asText()+"|"+n.path("distance").asText();}
}
