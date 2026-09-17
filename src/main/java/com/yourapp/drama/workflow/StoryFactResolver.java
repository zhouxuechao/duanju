package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves character-visible story facts at a story-time point without exposing world truth. */
@Service
public class StoryFactResolver {
    private final DocumentStore store;
    public StoryFactResolver(DocumentStore store){this.store=store;}

    public ObjectNode resolve(String projectId,double storyTime,List<String> characterIds){
        ObjectNode result=obj().put("storyTime",storyTime);ArrayNode visible=result.putArray("facts");
        Set<String> actors=new LinkedHashSet<>(characterIds==null?List.of():characterIds);
        for(ObjectNode fact:store.list(STORY_FACT,projectId,null)){
            if(!"ACTIVE".equalsIgnoreCase(text(fact,"status"))||!activeAt(fact,storyTime))continue;
            for(ObjectNode knowledge:store.list(CHARACTER_KNOWLEDGE,projectId,null)){
                if(!actors.contains(text(knowledge,"characterId"))||!id(fact).equals(text(knowledge,"factId")))continue;
                if(!"KNOWN".equalsIgnoreCase(text(knowledge,"knowledgeState"))||storyTime<number(knowledge,"knownFromStoryTime"))continue;
                ObjectNode copy=obj();for(String field:List.of("id","factKey","statement","subjectEntityId","predicate","objectEntityId","value","validFromStoryTime","validToStoryTime","revealedAtStoryTime"))if(fact.has(field))copy.set(field,fact.get(field).deepCopy());copy.put("knownBy",text(knowledge,"characterId"));visible.add(copy);break;
            }
        }
        return result;
    }
    private boolean activeAt(JsonNode fact,double time){return time>=number(fact,"validFromStoryTime")&&(!fact.has("validToStoryTime")||fact.path("validToStoryTime").isNull()||time<number(fact,"validToStoryTime"));}
    private double number(JsonNode node,String field){return node.path(field).isNumber()?node.path(field).asDouble(Double.POSITIVE_INFINITY):Double.POSITIVE_INFINITY;}
}
