package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class ProductionInputSnapshotService {
    private final DocumentStore store;
    private final DependencyRevisionService revisions;
    public ProductionInputSnapshotService(DocumentStore store,DependencyRevisionService revisions){this.store=store;this.revisions=revisions;}

    public ObjectNode freeze(String projectId,String sourceId,String sourceKind,JsonNode input){
        ObjectNode descriptor=describe(projectId,sourceId,sourceKind,input),snapshot=descriptor.deepCopy();String key=text(snapshot,"snapshotKey");
        for(ObjectNode existing:store.list(PRODUCTION_INPUT_SNAPSHOT,projectId,null))if(key.equals(text(existing,"snapshotKey")))return existing;
        snapshot.put("projectId",projectId);return store.create(PRODUCTION_INPUT_SNAPSHOT,snapshot);
    }

    public ObjectNode describe(String projectId,String sourceId,String sourceKind,JsonNode input){
        revisions.ensureBaseline(projectId);String effectiveSourceId=first(sourceId,text(input,"assetViewId"),text(input,"timelineId"),text(input,"episodeId"));ObjectNode frozen=obj().put("sourceKind",sourceKind);
        if(!effectiveSourceId.isBlank())frozen.put("sourceId",effectiveSourceId);
        String promptId=text(input,"promptVersionId"),scriptId=scriptId(effectiveSourceId,input);
        if(!promptId.isBlank())frozen.put("promptVersionId",promptId);if(!scriptId.isBlank())frozen.put("scriptVersionId",scriptId);
        frozen.put("scriptHash",scriptHash(input,scriptId));
        double storyTime=storyTime(effectiveSourceId,input);ChangePoint point=new ChangePoint(null,episodeNo(input),null,null,storyTime,null);
        ArrayNode characterVersions=frozen.putArray("characterDefinitionVersionIds");for(String value:ids(effectiveSourceId,input,"characterIds","CHARACTER_LOOK",true))addDefinition(characterVersions,CHARACTER,value,point);
        ArrayNode lookVersions=frozen.putArray("characterLookVersionIds");ids(effectiveSourceId,input,"characterLookIds","CHARACTER_LOOK",false).forEach(lookVersions::add);
        ArrayNode locationVersions=frozen.putArray("locationDefinitionVersionIds");for(String value:ids(effectiveSourceId,input,"locationIds","LOCATION",false))addDefinition(locationVersions,LOCATION,value,point);
        ArrayNode propVersions=frozen.putArray("propDefinitionVersionIds");for(String value:ids(effectiveSourceId,input,"propIds","PROP",false))addDefinition(propVersions,PROP,value,point);
        frozen.set("characterVersionIds",characterVersions.deepCopy());frozen.set("locationVersionIds",locationVersions.deepCopy());frozen.set("propVersionIds",propVersions.deepCopy());
        JsonNode storyFacts=input.path("context").path("storyFacts");ArrayNode storyFactVersions=frozen.putArray("storyFactVersions");if(storyFacts.isArray())for(JsonNode fact:storyFacts)storyFactVersions.add(obj().put("storyFactId",text(fact,"id")).put("factVersion",fact.path("factVersion").asInt(1)));
        frozen.put("storyFactHash",contentHash(storyFacts)).put("knowledgeSnapshotHash",contentHash(input.path("context").path("characterKnowledge")))
                .put("relationshipSnapshotHash",contentHash(input.path("context").path("relationships"))).put("continuitySnapshotHash",first(text(input,"continuitySnapshotHash"),contentHash(input.path("context").path("continuitySnapshot"))));
        if(input.path("upstreamProductionInputSnapshotIds").isArray())frozen.set("upstreamProductionInputSnapshotIds",input.path("upstreamProductionInputSnapshotIds").deepCopy());
        ArrayNode unknown=frozen.putArray("unknownFields");for(String field:List.of("scriptVersionId","promptVersionId"))if(!frozen.hasNonNull(field))unknown.add(field);
        String assetHash=DependencyRevisionService.hash(frozen);frozen.put("assetSnapshotHash",assetHash).put("snapshotKey",DependencyRevisionService.hash(obj().put("projectId",projectId).put("sourceKind",sourceKind).put("sourceId",effectiveSourceId).put("promptVersionId",promptId).put("assetSnapshotHash",assetHash)));
        return frozen;
    }

    public ObjectNode trace(ResourceKind sourceKind,String sourceId){
        ObjectNode source=store.get(sourceKind,sourceId);String snapshotId=text(source,"productionInputSnapshotId");
        if(snapshotId.isBlank()&&source.path("inputSnapshot").isObject())snapshotId=text(source.path("inputSnapshot"),"productionInputSnapshotId");
        if(snapshotId.isBlank()&&!text(source,"generationJobId").isBlank())snapshotId=text(store.get(GENERATION_JOB,text(source,"generationJobId")).path("inputSnapshot"),"productionInputSnapshotId");
        if(snapshotId.isBlank())throw new WorkflowException("PRODUCTION_SNAPSHOT_MISSING","该历史生成物没有可确认的生产输入快照");
        ObjectNode snapshot=store.get(PRODUCTION_INPUT_SNAPSHOT,snapshotId),result=snapshot.deepCopy();result.put("tracedFromKind",sourceKind.path()).put("tracedFromId",sourceId);return result;
    }

    private void addDefinition(ArrayNode target,ResourceKind kind,String entityId,ChangePoint point){if(entityId==null||entityId.isBlank())return;target.add(id(revisions.resolveDefinition(kind,entityId,point)));}
    private Set<String> ids(String sourceId,JsonNode input,String directField,String role,boolean characterFromLook){
        Set<String> values=new TreeSet<>();JsonNode shot=shot(sourceId,input);read(values,input.path(directField));read(values,input.path("context").path("shot").path(directField));read(values,shot.path(directField));
        if("locationIds".equals(directField)){add(values,text(input,"locationId"));add(values,text(input.path("context").path("shot"),"locationId"));add(values,text(shot,"locationId"));}
        if("characterLookIds".equals(directField)){input.path("context").path("shot").path("startState").path("characters").forEach(value->add(values,text(value,"lookId")));shot.path("startState").path("characters").forEach(value->add(values,text(value,"lookId")));}
        String assetKind=text(input,"assetKind"),assetId=text(input,"assetId");if(role.equals(assetKind)){if(characterFromLook)add(values,first(text(input.path("sourceSnapshot").path("character"),"id"),text(input,"characterId")));else add(values,assetId);}
        for(JsonNode ref:input.path("assetReferences"))if(role.equals(text(ref,"role"))){String value=characterFromLook?text(ref,"characterId"):text(ref,"assetId");if(!value.isBlank())values.add(value);}
        if(characterFromLook)for(JsonNode ref:input.path("assetReferences"))if("CHARACTER_LOOK".equals(text(ref,"role"))&&!text(ref,"assetId").isBlank())try{values.add(text(store.get(CHARACTER_LOOK,text(ref,"assetId")),"characterId"));}catch(RuntimeException ignored){}
        return values;
    }
    private JsonNode shot(String sourceId,JsonNode input){if(input.path("context").path("shot").isObject())return input.path("context").path("shot");if(sourceId!=null&&!sourceId.isBlank())try{return store.get(SHOT,sourceId);}catch(RuntimeException ignored){}return com.fasterxml.jackson.databind.node.MissingNode.getInstance();}
    private String scriptId(String sourceId,JsonNode input){String direct=first(text(input,"scriptVersionId"),text(input.path("context").path("episodeScript"),"storyDocumentId"),text(input.path("episode"),"storyDocumentId"));if(!direct.isBlank())return direct;JsonNode shot=shot(sourceId,input);if(shot.isObject())try{ObjectNode scene=store.get(SCENE,required(shot,"sceneId"));return text(store.get(EPISODE,required(scene,"episodeId")),"storyDocumentId");}catch(RuntimeException ignored){}return "";}
    private static void add(Set<String> target,String value){if(value!=null&&!value.isBlank())target.add(value);}
    private static void read(Set<String> target,JsonNode values){if(values.isArray())values.forEach(value->{if(value.isTextual()&&!value.asText().isBlank())target.add(value.asText());});}
    private double storyTime(String shotId,JsonNode input){JsonNode value=input.path("context").path("shot").path("storyTime");if(value.isNumber())return value.asDouble();if(shotId!=null&&!shotId.isBlank())try{return store.get(SHOT,shotId).path("storyTime").asDouble(0);}catch(RuntimeException ignored){}return 0;}
    private Integer episodeNo(JsonNode input){JsonNode value=input.path("context").path("episodeScript").path("episodeNo");if(!value.isIntegralNumber())value=input.path("episode").path("episodeNo");return value.isIntegralNumber()?value.asInt():null;}
    private String scriptHash(JsonNode input,String scriptId){JsonNode script=input.path("context").path("episodeScript");if(script.isObject()&&!script.isEmpty())return DependencyRevisionService.hash(script);if(!scriptId.isBlank())try{return DependencyRevisionService.hash(store.get(STORY_DOCUMENT,scriptId));}catch(RuntimeException ignored){}return "UNKNOWN";}
    private static String contentHash(JsonNode value){return value.isMissingNode()||value.isNull()||value.isEmpty()?"UNKNOWN":DependencyRevisionService.hash(value);}
    private static String first(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return "";}
}
