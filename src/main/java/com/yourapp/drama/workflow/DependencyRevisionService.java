package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class DependencyRevisionService {
    private static final Set<String> SYSTEM_FIELDS=Set.of("id","projectId","parentId","revision","createdAt","updatedAt","identityLocked");
    private final DocumentStore store;

    public DependencyRevisionService(DocumentStore store){this.store=store;}

    public void ensureBaseline(String projectId){
        store.transaction(()->{
            store.getForUpdate(PROJECT,projectId);
            for(ResourceKind kind:List.of(CHARACTER,LOCATION,PROP))for(ObjectNode entity:store.list(kind,projectId,null))
                if(store.list(definitionKind(kind),projectId,id(entity)).isEmpty())createDefinition(kind,entity,clean(entity),ChangeType.DEFINITION_REVISION,ChangeScope.GLOBAL,ChangePoint.origin(),"MIGRATED_BASELINE","SYSTEM",null);
            for(ObjectNode look:store.list(CHARACTER_LOOK,projectId,null))if(!look.path("version").isIntegralNumber()){
                List<ObjectNode> looks=store.list(CHARACTER_LOOK,projectId,required(look,"characterId"));
                looks.sort(Comparator.comparing(Documents::createdAt).thenComparing(Documents::id));
                int version=looks.indexOf(look)+1;ObjectNode next=look.deepCopy().put("version",version).put("changeType",ChangeType.DEFINITION_REVISION.name()).put("validScope",ChangeScope.GLOBAL.name()).put("changeReason","MIGRATED_BASELINE").put("changedBy","SYSTEM").put("contentHash",hash(clean(look)));
                if(!next.path("validFromStoryTime").isNumber())next.put("validFromStoryTime",0);
                store.update(CHARACTER_LOOK,id(look),revision(look),next);
            }
            return null;
        });
    }

    public ObjectNode recordDefinitionRevision(ResourceKind entityKind,String entityId,ObjectNode definition,ChangeType changeType,ChangeScope scope,ChangePoint requestedPoint,String reason,String changedBy){
        if(changeType==ChangeType.STORY_STATE_TRANSITION)throw new IllegalArgumentException("剧情状态变化不能写入 Definition Version");
        return store.transaction(()->{
            ObjectNode entity=store.get(entityKind,entityId);String projectId=project(entity);ensureBaseline(projectId);
            ChangePoint point=effectivePoint(projectId,entityKind,entityId,scope,requestedPoint);
            List<ObjectNode> versions=new ArrayList<>(store.list(definitionKind(entityKind),projectId,entityId));versions.sort(Comparator.comparingInt(value->value.path("version").asInt()));
            ObjectNode previous=versions.isEmpty()?null:versions.getLast();
            ObjectNode saved=createDefinition(entityKind,entity,definition,changeType,scope,point,reason,changedBy,previous);
            ArrayNode affected=analyzeAndRecord(projectId,entityKind,entityId,scope,point,id(saved));
            ObjectNode result=saved.deepCopy();result.set("affectedNodes",affected);return result;
        });
    }

    public ObjectNode recordCharacterLookVersion(String characterId,ObjectNode lookDefinition,ChangePoint point,String reason,String changedBy){
        return store.transaction(()->{
            ObjectNode character=store.get(CHARACTER,characterId);String projectId=project(character);ensureBaseline(projectId);
            List<ObjectNode> looks=new ArrayList<>(store.list(CHARACTER_LOOK,projectId,characterId));looks.sort(Comparator.comparingInt(value->value.path("version").asInt()));
            ObjectNode previous=looks.isEmpty()?null:looks.getLast();double from=requiredStoryTime(point);
            if(previous!=null&&!previous.path("validToStoryTime").isNumber()&&from>previous.path("validFromStoryTime").asDouble(0))store.update(CHARACTER_LOOK,id(previous),revision(previous),previous.deepCopy().put("validToStoryTime",from));
            ObjectNode value=clean(lookDefinition).put("projectId",projectId).put("characterId",characterId).put("version",previous==null?1:previous.path("version").asInt()+1)
                    .put("changeType",ChangeType.STORY_STATE_TRANSITION.name()).put("validScope",ChangeScope.FROM_STORY_TIME.name()).put("validFromStoryTime",from)
                    .put("changeReason",reason).put("changedBy",changedBy).put("contentHash",hash(clean(lookDefinition)));
            value.set("changePoint",point.toJson());if(previous!=null)value.put("supersedesId",id(previous));
            return store.create(CHARACTER_LOOK,value);
        });
    }

    public ObjectNode resolveDefinition(ResourceKind entityKind,String entityId,ChangePoint point){
        ObjectNode entity=store.get(entityKind,entityId);ensureBaseline(project(entity));
        return store.list(definitionKind(entityKind),project(entity),entityId).stream().filter(value->active(value,point))
                .max(Comparator.comparingInt(value->value.path("version").asInt())).orElseThrow(()->new WorkflowException("DEFINITION_VERSION_MISSING","找不到当前剧情点的定义版本"));
    }

    public ObjectNode resolveCharacterLook(String characterId,double storyTime){
        ObjectNode character=store.get(CHARACTER,characterId);ensureBaseline(project(character));
        return store.list(CHARACTER_LOOK,project(character),characterId).stream().filter(value->storyTime>=value.path("validFromStoryTime").asDouble(0)&&(!value.path("validToStoryTime").isNumber()||storyTime<value.path("validToStoryTime").asDouble()))
                .max(Comparator.comparingInt(value->value.path("version").asInt())).orElseThrow(()->new WorkflowException("CHARACTER_LOOK_VERSION_MISSING","找不到当前故事时间的人物造型版本"));
    }

    public ObjectNode recordStoryStateTransition(ResourceKind entityKind,String entityId,ObjectNode state,ChangePoint point,String reason){
        ResourceKind stateKind=stateKind(entityKind);double from=requiredStoryTime(point);
        return store.transaction(()->{
            ObjectNode entity=store.get(entityKind,entityId);String projectId=project(entity);ensureBaseline(projectId);
            List<ObjectNode> history=new ArrayList<>(store.list(stateKind,projectId,entityId));history.sort(Comparator.comparingDouble(value->value.path("validFromStoryTime").asDouble()));
            if(!history.isEmpty()){
                ObjectNode previous=history.getLast();double previousFrom=previous.path("validFromStoryTime").asDouble();
                if(from<=previousFrom)throw new WorkflowException("STORY_STATE_TIME_CONFLICT","剧情状态必须追加在现有状态之后");
                if(!previous.path("validToStoryTime").isNumber())store.update(stateKind,id(previous),revision(previous),previous.deepCopy().put("validToStoryTime",from));
            }
            ObjectNode value=state.deepCopy().put("projectId",projectId).put(entityKind==CHARACTER?"characterId":entityKind==LOCATION?"locationId":"propId",entityId)
                    .put("validFromStoryTime",from).put("changeType",ChangeType.STORY_STATE_TRANSITION.name()).put("validScope",ChangeScope.FROM_STORY_TIME.name()).put("changeReason",reason);
            value.set("changePoint",point.toJson());ObjectNode saved=store.create(stateKind,value);
            ObjectNode result=saved.deepCopy();result.set("affectedNodes",analyzeAndRecord(projectId,entityKind,entityId,ChangeScope.FROM_STORY_TIME,point,id(saved)));return result;
        });
    }

    public ObjectNode registerDependency(String projectId,ResourceKind sourceKind,String sourceId,ResourceKind targetKind,String targetId,String dependencyType,ChangePoint point){
        ObjectNode source=store.get(sourceKind,sourceId),target=store.get(targetKind,targetId);
        if(!projectId.equals(project(source))||!projectId.equals(project(target)))throw new IllegalArgumentException("依赖边两端必须属于同一项目");
        for(ObjectNode edge:store.list(DEPENDENCY_EDGE,projectId,null))if(text(edge,"sourceKind").equals(sourceKind.path())&&text(edge,"sourceId").equals(sourceId)&&text(edge,"targetKind").equals(targetKind.path())&&text(edge,"targetId").equals(targetId)&&text(edge,"dependencyType").equals(dependencyType))return edge;
        ObjectNode edge=obj().put("projectId",projectId).put("sourceKind",sourceKind.path()).put("sourceId",sourceId).put("targetKind",targetKind.path()).put("targetId",targetId).put("dependencyType",dependencyType);
        copyPoint(edge,point);edge.set("changePoint",point.toJson());return store.create(DEPENDENCY_EDGE,edge);
    }

    public ArrayNode markForwardDependencies(String projectId,ResourceKind sourceKind,String sourceId,ChangeScope scope,ChangePoint point,String sourceVersionId,Set<String> dependencyTypes){
        ArrayNode result=obj().putArray("affected");
        for(ObjectNode edge:store.list(DEPENDENCY_EDGE,projectId,null)){
            if(!text(edge,"sourceKind").equals(sourceKind.path())||!text(edge,"sourceId").equals(sourceId)||!forward(edge,scope,point))continue;
            String type=text(edge,"dependencyType");if(dependencyTypes!=null&&!dependencyTypes.isEmpty()&&!dependencyTypes.contains(type))continue;
            ObjectNode node=obj().put("resourceKind",text(edge,"targetKind")).put("resourceId",text(edge,"targetId")).put("dependencyType",type).put("recommendedAction","REVALIDATION_REQUIRED");
            for(String field:List.of("episodeId","episodeNo","sceneId","sceneNo","shotId","storyTime"))if(edge.has(field))node.set(field,edge.path(field).deepCopy());result.add(node);
            boolean exists=store.list(REVALIDATION_MARKER,projectId,null).stream().anyMatch(marker->text(marker,"resourceKind").equals(text(edge,"targetKind"))&&text(marker,"resourceId").equals(text(edge,"targetId"))&&text(marker,"sourceVersionId").equals(sourceVersionId));
            if(!exists){ObjectNode marker=node.deepCopy().put("projectId",projectId).put("sourceKind",sourceKind.path()).put("sourceId",sourceId).put("sourceVersionId",sourceVersionId).put("status","REVALIDATION_REQUIRED");store.create(REVALIDATION_MARKER,marker);}
        }
        return result;
    }

    private ArrayNode analyzeAndRecord(String projectId,ResourceKind sourceKind,String sourceId,ChangeScope scope,ChangePoint point,String sourceVersionId){
        ArrayNode affected=Documents.obj().putArray("values");
        for(ObjectNode edge:store.list(DEPENDENCY_EDGE,projectId,null)){
            if(!text(edge,"sourceKind").equals(sourceKind.path())||!text(edge,"sourceId").equals(sourceId)||!forward(edge,scope,point))continue;
            ObjectNode node=obj().put("resourceKind",text(edge,"targetKind")).put("resourceId",text(edge,"targetId")).put("reason","上游版本发生变化").put("dependencyType",text(edge,"dependencyType")).put("recommendedAction","REVALIDATION_REQUIRED");
            for(String field:List.of("episodeId","episodeNo","sceneId","sceneNo","shotId","storyTime"))if(edge.has(field))node.set(field,edge.path(field).deepCopy());affected.add(node);
            boolean exists=store.list(REVALIDATION_MARKER,projectId,null).stream().anyMatch(marker->text(marker,"resourceKind").equals(text(edge,"targetKind"))&&text(marker,"resourceId").equals(text(edge,"targetId"))&&text(marker,"sourceVersionId").equals(sourceVersionId));
            if(!exists){ObjectNode marker=node.deepCopy().put("projectId",projectId).put("sourceKind",sourceKind.path()).put("sourceId",sourceId).put("sourceVersionId",sourceVersionId).put("status","REVALIDATION_REQUIRED");store.create(REVALIDATION_MARKER,marker);}
        }
        ArrayNode result=Documents.obj().putArray("result");affected.forEach(result::add);return result;
    }

    private ObjectNode createDefinition(ResourceKind entityKind,ObjectNode entity,ObjectNode definition,ChangeType type,ChangeScope scope,ChangePoint point,String reason,String changedBy,ObjectNode previous){
        ResourceKind versionKind=definitionKind(entityKind);ObjectNode clean=clean(definition),value=clean.deepCopy();
        value.put("projectId",project(entity)).put(entityKind==CHARACTER?"characterId":entityKind==LOCATION?"locationId":"propId",id(entity))
                .put("version",previous==null?1:previous.path("version").asInt()+1).put("validScope",scope.name()).put("changeType",type.name()).put("changeReason",reason).put("changedBy",changedBy).put("contentHash",hash(clean));
        copyPoint(value,point);value.set("changePoint",point.toJson());value.set("definition",clean.deepCopy());if(previous!=null)value.put("supersedesId",id(previous));
        return store.create(versionKind,value);
    }

    private ChangePoint effectivePoint(String projectId,ResourceKind sourceKind,String sourceId,ChangeScope scope,ChangePoint requested){
        ChangePoint point=requested==null?ChangePoint.origin():requested;if(scope!=ChangeScope.FROM_FIRST_APPEARANCE)return scope==ChangeScope.GLOBAL?ChangePoint.origin():point;
        return store.list(DEPENDENCY_EDGE,projectId,null).stream().filter(edge->text(edge,"sourceKind").equals(sourceKind.path())&&text(edge,"sourceId").equals(sourceId))
                .min(Comparator.comparingDouble(this::position)).map(edge->new ChangePoint(text(edge,"episodeId"),edge.has("episodeNo")?edge.path("episodeNo").asInt():null,text(edge,"sceneId"),edge.has("sceneNo")?edge.path("sceneNo").asInt():null,edge.has("storyTime")?edge.path("storyTime").asDouble():null,requested==null?null:requested.scriptVersionId())).orElse(point);
    }
    private boolean forward(JsonNode edge,ChangeScope scope,ChangePoint point){if(scope==ChangeScope.GLOBAL||scope==ChangeScope.FROM_FIRST_APPEARANCE)return true;return position(edge)>=position(point);}
    private double position(JsonNode value){if(value.path("storyTime").isNumber())return value.path("storyTime").asDouble();return value.path("episodeNo").asDouble(0)*1_000_000d+value.path("sceneNo").asDouble(0)*1_000d;}
    private double position(ChangePoint point){if(point.storyTime()!=null)return point.storyTime();return (point.episodeNo()==null?0:point.episodeNo())*1_000_000d+(point.sceneNo()==null?0:point.sceneNo())*1_000d;}
    private boolean active(JsonNode value,ChangePoint point){return position(value)<=position(point);}
    private static double requiredStoryTime(ChangePoint point){if(point==null||point.storyTime()==null||!Double.isFinite(point.storyTime()))throw new IllegalArgumentException("Story State/Look Version 必须有明确 storyTime");return point.storyTime();}
    private static void copyPoint(ObjectNode target,ChangePoint point){if(point==null)return;if(point.episodeId()!=null&&!point.episodeId().isBlank())target.put("episodeId",point.episodeId());if(point.episodeNo()!=null)target.put("effectiveFromEpisode",point.episodeNo()).put("episodeNo",point.episodeNo());if(point.sceneId()!=null&&!point.sceneId().isBlank())target.put("sceneId",point.sceneId());if(point.sceneNo()!=null)target.put("effectiveFromScene",point.sceneNo()).put("sceneNo",point.sceneNo());if(point.storyTime()!=null&&Double.isFinite(point.storyTime()))target.put("effectiveFromStoryTime",point.storyTime()).put("storyTime",point.storyTime());if(point.scriptVersionId()!=null&&!point.scriptVersionId().isBlank())target.put("scriptVersionId",point.scriptVersionId());}
    private static ResourceKind definitionKind(ResourceKind kind){return switch(kind){case CHARACTER->CHARACTER_DEFINITION_VERSION;case LOCATION->LOCATION_DEFINITION_VERSION;case PROP->PROP_DEFINITION_VERSION;default->throw new IllegalArgumentException("只有 Character/Location/Prop 支持 Definition Version");};}
    private static ResourceKind stateKind(ResourceKind kind){return switch(kind){case CHARACTER->CHARACTER_STATE;case LOCATION->LOCATION_STATE;case PROP->PROP_STATE;default->throw new IllegalArgumentException("只有 Character/Location/Prop 支持 Story State");};}
    private static ObjectNode clean(JsonNode source){ObjectNode result=obj();source.fields().forEachRemaining(entry->{if(!SYSTEM_FIELDS.contains(entry.getKey()))result.set(entry.getKey(),entry.getValue().deepCopy());});return result;}
    static String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode result=obj();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,canonical(value.path(name)));return result;}if(value.isArray()){ArrayNode result=Documents.obj().putArray("v");value.forEach(item->result.add(canonical(item)));return result;}return value.deepCopy();}
}
