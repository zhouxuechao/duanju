package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Guards the atomic production unit: one provider task renders one authored Shot into one Take. */
@Component
public class VideoRequestContractValidator {
    public void validateOneShot(String shotId,JsonNode materials){
        if(shotId==null||shotId.isBlank())throw new IllegalArgumentException("SHOT_ID_REQUIRED");
        if(materials==null||!materials.isArray())return;
        for(JsonNode material:materials){String owner=material.path("shotId").asText("");if(!owner.isBlank()&&!shotId.equals(owner))throw new IllegalArgumentException("ONE_SHOT_ONE_TAKE_VIOLATION: material "+material.path("id").asText()+" belongs to "+owner);}
    }
}
