package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves the latest relationship interval active at a story-time point. */
@Service
public class RelationshipResolver {
    private final DocumentStore store;
    public RelationshipResolver(DocumentStore store){this.store=store;}
    public ObjectNode resolve(String projectId,double storyTime,List<String> characterIds){
        ObjectNode result=obj().put("storyTime",storyTime);ArrayNode out=result.putArray("relationships");Set<String> actors=new HashSet<>(characterIds==null?List.of():characterIds);Map<String,ObjectNode> selected=new LinkedHashMap<>();
        for(ObjectNode relation:store.list(RELATIONSHIP,projectId,null)){
            String a=text(relation,"subjectCharacterId"),b=text(relation,"objectCharacterId");if(!actors.contains(a)&&!actors.contains(b))continue;if(!activeAt(relation,storyTime))continue;
            String pair=a.compareTo(b)<=0?a+":"+b:b+":"+a,key=pair+":"+text(relation,"relationshipType");ObjectNode current=selected.get(key);if(current==null||number(relation,"validFromStoryTime")>number(current,"validFromStoryTime"))selected.put(key,relation);
        }
        selected.values().forEach(relation->{ObjectNode copy=obj();for(String field:List.of("id","subjectCharacterId","objectCharacterId","relationshipType","state","trustLevel","conflictLevel","intimacyLevel","powerBalance","lastChangedEpisode","lastChangedScene","changeReason","validFromStoryTime","validToStoryTime","sourceSceneId","sourceShotId"))if(relation.has(field))copy.set(field,relation.get(field).deepCopy());out.add(copy);});return result;
    }
    private boolean activeAt(JsonNode relation,double time){return time>=number(relation,"validFromStoryTime")&&(!relation.has("validToStoryTime")||relation.path("validToStoryTime").isNull()||time<number(relation,"validToStoryTime"));}
    private double number(JsonNode node,String field){return node.path(field).isNumber()?node.path(field).asDouble(Double.POSITIVE_INFINITY):Double.POSITIVE_INFINITY;}
}
