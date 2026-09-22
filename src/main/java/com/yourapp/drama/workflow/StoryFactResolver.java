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
        ObjectNode result=obj().put("storyTime",storyTime);ArrayNode visible=result.putArray("facts"),audience=result.putArray("audienceKnowledge"),hidden=result.putArray("hiddenTruths"),suspicions=result.putArray("suspicions"),misunderstandings=result.putArray("misunderstandings"),foreshadowing=result.putArray("foreshadowing"),payoffs=result.putArray("payoffs");ObjectNode byCharacter=result.putObject("characterKnowledge");
        Set<String> actors=new LinkedHashSet<>(characterIds==null?List.of():characterIds);
        for(String actor:actors){ObjectNode knowledge=byCharacter.putObject(actor);knowledge.putArray("knownFacts");knowledge.putArray("suspicions");knowledge.putArray("misunderstandings");knowledge.putArray("unknownFacts");}
        for(ObjectNode fact:store.list(STORY_FACT,projectId,null)){
            ObjectNode resolved=resolveFact(id(fact),storyTime,true);int factVersion=resolved.path("factVersion").asInt(1);JsonNode mutations=resolved.path("appliedMutationIds");
            if(!"ACTIVE".equalsIgnoreCase(text(resolved,"status"))||!activeAt(resolved,storyTime))continue;
            ObjectNode projection=projection(resolved,factVersion,mutations);double reveal=resolved.path("revealedAtStoryTime").asDouble(Double.POSITIVE_INFINITY);String role=text(resolved,"narrativeRole");
            if(storyTime>=reveal)audience.add(projection.deepCopy());else if("HIDDEN_TRUTH".equals(role)||Double.isFinite(reveal))hidden.add(projection.deepCopy());
            if("FORESHADOWING".equals(role))foreshadowing.add(projection.deepCopy());if("PAYOFF".equals(role)&&storyTime>=reveal)payoffs.add(projection.deepCopy());
            ArrayNode knownBy=JsonNodeFactory.instance.arrayNode();
            for(ObjectNode knowledge:store.list(CHARACTER_KNOWLEDGE,projectId,null)){
                if(!actors.contains(text(knowledge,"characterId"))||!id(fact).equals(text(knowledge,"factId")))continue;
                String actor=text(knowledge,"characterId"),state=text(knowledge,"knowledgeState");if(storyTime<number(knowledge,"knownFromStoryTime"))continue;ObjectNode bucket=(ObjectNode)byCharacter.path(actor);
                if("KNOWN".equalsIgnoreCase(state)){bucket.withArray("knownFacts").add(id(fact));knownBy.add(actor);}
                else if("SUSPECTED".equalsIgnoreCase(state)){bucket.withArray("suspicions").add(id(fact));suspicions.add(obj().put("characterId",actor).put("factId",id(fact)).put("confidence",knowledge.path("confidence").asDouble(.5)));}
                else if("MISUNDERSTOOD".equalsIgnoreCase(state)){bucket.withArray("misunderstandings").add(id(fact));misunderstandings.add(obj().put("characterId",actor).put("factId",id(fact)).put("believedStatement",text(knowledge,"believedStatement")));}
                else bucket.withArray("unknownFacts").add(id(fact));
            }
            if(!knownBy.isEmpty()){projection.set("knownBy",knownBy);visible.add(projection);}
        }
        return result;
    }
    private ObjectNode projection(JsonNode fact,int version,JsonNode mutations){ObjectNode copy=obj();for(String field:List.of("id","factKey","statement","subjectEntityId","predicate","objectEntityId","value","validFromStoryTime","validToStoryTime","revealedAtStoryTime","narrativeRole"))if(fact.has(field))copy.set(field,fact.get(field).deepCopy());copy.put("factVersion",version);copy.set("appliedMutationIds",mutations.deepCopy());return copy;}
    public ObjectNode resolveFactAt(String factId,double storyTime){return resolveFact(factId,storyTime,true);}
    public ObjectNode resolveFactBefore(String factId,double storyTime){return resolveFact(factId,storyTime,false);}
    private ObjectNode resolveFact(String factId,double storyTime,boolean inclusive){ObjectNode fact=store.get(STORY_FACT,factId).deepCopy();ArrayNode applied=JsonNodeFactory.instance.arrayNode();int version=1;for(ObjectNode mutation:store.list(STORY_FACT_MUTATION,project(fact),factId)){double effective=mutation.path("effectiveFromStoryTime").asDouble(Double.POSITIVE_INFINITY);if(effective>storyTime||(!inclusive&&effective==storyTime))continue;apply(fact,mutation);applied.add(id(mutation));version++;}fact.put("factVersion",version);fact.set("appliedMutationIds",applied);return fact;}
    private void apply(ObjectNode fact,JsonNode mutation){String operation=text(mutation,"operation");if("RETRACT".equals(operation)){fact.put("status","RETRACTED");return;}if("REACTIVATE".equals(operation)){fact.put("status","ACTIVE");return;}JsonNode changes=mutation.path("after").isObject()?mutation.path("after"):mutation.path("changes");for(String field:List.of("statement","predicate","subjectEntityId","objectEntityId","value","status","validToStoryTime","revealedAtStoryTime"))if(changes.has(field))fact.set(field,changes.path(field).deepCopy());}
    private boolean activeAt(JsonNode fact,double time){return time>=number(fact,"validFromStoryTime")&&(!fact.has("validToStoryTime")||fact.path("validToStoryTime").isNull()||time<number(fact,"validToStoryTime"));}
    private double number(JsonNode node,String field){return node.path(field).isNumber()?node.path(field).asDouble(Double.POSITIVE_INFINITY):Double.POSITIVE_INFINITY;}
}
