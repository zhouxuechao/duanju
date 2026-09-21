package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReferenceConflictValidator {
    public void validate(JsonNode refs){
        if(refs==null||!refs.isArray())return;
        Map<String,JsonNode> owners=new HashMap<>();
        for(JsonNode ref:refs){String group=ref.path("authorityGroup").asText("");String entity=ref.path("entityId").asText("");int priority=ref.path("authorityPriority").asInt();
            for(JsonNode control:ref.path("controls")){String key=entity+"|"+control.asText();JsonNode previous=owners.get(key);if(previous==null){owners.put(key,ref);continue;}
                String previousGroup=previous.path("authorityGroup").asText("");if(!group.isBlank()&&group.equals(previousGroup))continue;
                if(previous.path("authorityPriority").asInt()==priority)throw new IllegalArgumentException("REFERENCE_AUTHORITY_CONFLICT: "+key+" is controlled by "+previous.path("id").asText()+" and "+ref.path("id").asText());
                if(priority>previous.path("authorityPriority").asInt())owners.put(key,ref);
            }
        }
    }
}
