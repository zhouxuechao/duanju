package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Selects a temporary voice state at the requested story time, falling back to the profile. */
@Service
public class VoiceStateResolver {
    private final DocumentStore store;
    public VoiceStateResolver(DocumentStore store) { this.store=store; }
    public ObjectNode resolve(String projectId,String voiceProfileId,double storyTime) {
        ObjectNode result=obj().put("voiceProfileId",voiceProfileId).put("storyTime",storyTime);
        ObjectNode selected=null;
        for(ObjectNode state:store.list(VOICE_STATE,projectId,voiceProfileId)) if(activeAt(state,storyTime)&&(selected==null||number(state,"validFromStoryTime")>number(selected,"validFromStoryTime"))) selected=state;
        if(selected!=null) for(String field:List.of("id","state","providerVoiceId","referenceAudioUrl","validFromStoryTime","validToStoryTime")) if(selected.has(field))result.set(field,selected.get(field).deepCopy());
        return result;
    }
    private boolean activeAt(JsonNode n,double t){return t>=number(n,"validFromStoryTime")&&(!n.has("validToStoryTime")||n.path("validToStoryTime").isNull()||t<number(n,"validToStoryTime"));}
    private double number(JsonNode n,String f){return n.path(f).isNumber()?n.path(f).asDouble():Double.NEGATIVE_INFINITY;}
}
