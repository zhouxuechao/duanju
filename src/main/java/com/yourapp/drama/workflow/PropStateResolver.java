package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.PROP_STATE;
import static com.yourapp.drama.workflow.Documents.obj;

/** Resolves ownership, condition and hand placement at the requested story time. */
@Service
public class PropStateResolver {
    private final DocumentStore store;
    public PropStateResolver(DocumentStore store){this.store=store;}
    public ObjectNode resolve(String projectId,String propId,double storyTime){
        ObjectNode result=obj().put("propId",propId).put("storyTime",storyTime),selected=null;
        for(ObjectNode state:store.list(PROP_STATE,projectId,propId))if(active(state,storyTime)&&(selected==null||number(state,"validFromStoryTime")>number(selected,"validFromStoryTime")))selected=state;
        if(selected!=null)for(String field:List.of("id","state","owner","location","condition","visible","carriedBy","heldByHand","validFromStoryTime","validToStoryTime","sourceSceneId","sourceShotId"))if(selected.has(field))result.set(field,selected.get(field).deepCopy());
        return result;
    }
    private boolean active(JsonNode value,double time){return time>=number(value,"validFromStoryTime")&&(!value.has("validToStoryTime")||value.path("validToStoryTime").isNull()||time<number(value,"validToStoryTime"));}
    private double number(JsonNode value,String field){return value.path(field).isNumber()?value.path(field).asDouble():Double.NEGATIVE_INFINITY;}
}
