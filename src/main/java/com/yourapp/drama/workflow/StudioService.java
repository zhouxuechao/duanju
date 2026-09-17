package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.production.DirectorStyleResolver;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;
import static com.yourapp.drama.persistence.ResourceKind.*;

@Service
public class StudioService {
    private final DocumentStore store;
    private final DirectorStyleResolver directorStyles;
    private static final Set<ResourceKind> EDITABLE=EnumSet.of(PROJECT,SHOT,DIALOGUE_LINE,VOICE_PROFILE,VOICE_STATE,TIMELINE,TIMELINE_ITEM,STORY_FACT,CHARACTER_KNOWLEDGE,RELATIONSHIP,LOCATION_STATE,ENTITY_ALIAS,CHARACTER_STATE,DIALECT_DICTIONARY,DIALECT_PHRASE,DIALECT_EXAMPLE,DIALECT_CORRECTION);
    private static final Set<String> PROTECTED=Set.of("id","createdAt","updatedAt","revision","status","locked","selected","qcStatus","providerUrl","archiveUrl","generationJobId","providerRequestId","handoffStatus","providerUrlExpiresAt","identityLocked","activeStoryDocumentId","activeStoryBibleId","storyDocumentId","storyBibleId","continuitySnapshot","continuityHash","stale","assetReferencesStale","assetViewIds","directorPlanVersion","dramaticBeatVersion","shotPlanVersion","directorPlanSnapshot","dramaticBeatSnapshot","shotPlanSnapshot");
    public StudioService(DocumentStore store,DirectorStyleResolver directorStyles) { this.store=store;this.directorStyles=directorStyles; }
    public List<ObjectNode> list(ResourceKind kind,String projectId,String parentId) { return store.list(kind,projectId,parentId); }
    public ObjectNode get(ResourceKind kind,String id) { return store.get(kind,id); }
    public ObjectNode create(ResourceKind kind,ObjectNode request) {
        editable(kind);
        ObjectNode body=request.deepCopy(); PROTECTED.forEach(body::remove);
        validate(kind,body);
        if (kind==SHOT) {
            body.put("status","PLANNED");
            body.putArray("statusHistory").add(obj().put("to","PLANNED").put("at",java.time.Instant.now().toString()));
            return store.transaction(()->{store.getForUpdate(SCENE,required(body,"sceneId"));body.putIfAbsent("shotNo",IntNode.valueOf(store.list(SHOT,null,required(body,"sceneId")).size()+1));body.putIfAbsent("presentationOrder",body.path("shotNo"));return store.create(kind,body);});
        }
        if (kind==PROJECT) body.put("status","IDEA");
        if (kind==CHARACTER) body.put("identityLocked",false);
        return store.create(kind,body);
    }
    public ObjectNode update(ResourceKind kind,String id,ObjectNode request) {
        editable(kind);
        long expected=request.path("revision").asLong(-1);
        if(expected<1) throw new IllegalArgumentException("修改必须包含当前 revision，避免覆盖其他修改");
        return store.transaction(() -> {
            ObjectNode current=store.getForUpdate(kind,id);
            if(current.path("locked").asBoolean() || current.path("identityLocked").asBoolean()) throw new WorkflowException("LOCKED","已锁定的资产不能直接修改，请创建新版本");
            if(kind==SHOT && !Set.of("DRAFT","PLANNED","NEEDS_REPAIR","FAILED","PROVIDER_URL_EXPIRED").contains(text(current,"status")))
                throw new WorkflowException("SHOT_IN_PRODUCTION","镜头已有生产结果，请先发起修改，再生成新版本");
            ObjectNode patch=request.deepCopy(); PROTECTED.forEach(patch::remove);
            if(patch.has("projectId") && !text(patch,"projectId").equals(text(current,"projectId"))) throw new IllegalArgumentException("不能把记录移到另一项目");
            if(kind.parentField()!=null && patch.has(kind.parentField()) && !text(patch,kind.parentField()).equals(text(current,kind.parentField()))) throw new IllegalArgumentException("不能修改所属对象");
            ObjectNode next=merge(current,patch); validate(kind,next);
            return store.update(kind,id,expected,next);
        });
    }
    public ObjectNode workspace(String projectId) {
        ObjectNode result=obj(); result.set("project",store.get(PROJECT,projectId));
        for(ResourceKind kind:ResourceKind.values()) if(kind!=PROJECT) {
            ArrayNode array=result.putArray(kind.path()); store.list(kind,projectId,null).forEach(array::add);
        }
        return result;
    }
    private void editable(ResourceKind kind) {
        if(!EDITABLE.contains(kind)) throw new WorkflowException("SYSTEM_RESOURCE","生成记录只能由生产流程创建和更新");
    }
    private void validate(ResourceKind kind,ObjectNode body) {
        switch(kind) {
            case PROJECT -> { required(body,"name"); required(body,"idea");
                if(body.has("episodeCount")&&!body.path("episodeCount").isIntegralNumber())throw new IllegalArgumentException("episodeCount 必须为整数");
                int count=body.path("episodeCount").asInt(1); if(count<1||count>100)throw new IllegalArgumentException("集数应为 1 到 100");
                body.put("episodeCount",count); body.putIfAbsent("targetDuration",IntNode.valueOf(20)); body.putIfAbsent("ratio",TextNode.valueOf("9:16")); body.putIfAbsent("style",TextNode.valueOf("写实电影")); body.putIfAbsent("dialect",TextNode.valueOf("MANDARIN"));
                if(!body.path("directorStyleProfile").isObject())body.set("directorStyleProfile",directorStyles.defaults());
                if(!body.path("targetDuration").isNumber())throw new IllegalArgumentException("targetDuration 必须为数字");
                if(body.path("targetDuration").asDouble()<10||body.path("targetDuration").asDouble()>1800)throw new IllegalArgumentException("单集时长应为 10 到 1800 秒");
            }
            case EPISODE, SCENE, LOCATION, PROP, VOICE_PROFILE -> required(body,"name");
            case STORY_FACT -> { required(body,"factKey"); required(body,"predicate"); required(body,"statement"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); body.putIfAbsent("status", TextNode.valueOf("ACTIVE")); }
            case CHARACTER_KNOWLEDGE -> { required(body,"characterId"); required(body,"factId"); required(body,"knowledgeState"); if(!Set.of("KNOWN","UNKNOWN").contains(text(body,"knowledgeState")))throw new IllegalArgumentException("knowledgeState 只能为 KNOWN 或 UNKNOWN"); if(!body.path("knownFromStoryTime").isNumber())throw new IllegalArgumentException("knownFromStoryTime 必须为数字"); }
            case RELATIONSHIP -> { required(body,"subjectCharacterId"); required(body,"objectCharacterId"); required(body,"relationshipType"); required(body,"state"); if(text(body,"subjectCharacterId").equals(text(body,"objectCharacterId")))throw new IllegalArgumentException("人物关系不能连接同一人物"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("人物关系必须包含 projectId"); sameProject(CHARACTER,text(body,"subjectCharacterId"),p); sameProject(CHARACTER,text(body,"objectCharacterId"),p); }
            case LOCATION_STATE -> { required(body,"locationId"); required(body,"state"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("地点状态必须包含 projectId"); sameProject(LOCATION,text(body,"locationId"),p); }
            case VOICE_STATE -> { required(body,"voiceProfileId"); required(body,"state"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("声音状态必须包含 projectId"); sameProject(VOICE_PROFILE,text(body,"voiceProfileId"),p); if(text(body,"providerVoiceId").isBlank()&&text(body,"referenceAudioUrl").isBlank())throw new IllegalArgumentException("声音状态必须包含 providerVoiceId 或 referenceAudioUrl"); }
            case ENTITY_ALIAS -> { required(body,"entityId"); required(body,"alias"); required(body,"aliasType"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("实体别名必须包含 projectId"); sameProject(CHARACTER,text(body,"entityId"),p); }
            case CHARACTER -> {
                required(body,"name"); String source=body.path("sourceType").asText("PUBLIC_VIRTUAL_HUMAN");
                if(!Set.of("PUBLIC_VIRTUAL_HUMAN","IMAGE_REFERENCE").contains(source))throw new IllegalArgumentException("人物来源应为火山虚拟人物或图片参考角色");
                body.put("provider",source.equals("IMAGE_REFERENCE")?"SEEDREAM":"VOLCENGINE"); body.put("sourceType",source);
                body.putIfAbsent("providerStatus",TextNode.valueOf("UNBOUND"));
                if(!Set.of("UNBOUND","AVAILABLE","UNAVAILABLE","EXPIRED","PENDING").contains(text(body,"providerStatus"))) throw new IllegalArgumentException("人物素材状态无效");
                if(text(body,"providerStatus").equals("AVAILABLE")&&source.equals("PUBLIC_VIRTUAL_HUMAN")) required(body,"providerAssetId");
                if(source.equals("IMAGE_REFERENCE")) required(body,"referenceImageUrl");
            }
            case SHOT -> {
                required(body,"action"); required(body,"purpose");
                double duration=body.path("duration").asDouble(3); if(!Double.isFinite(duration)||duration<2||duration>5)throw new IllegalArgumentException("请拆成每个 2～5 秒的原子镜头"); body.put("duration",duration);
                try { ShotRelation.valueOf(required(body,"relationToPrevious")); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("shot.relationToPrevious 不在允许值中："+Arrays.toString(ShotRelation.values())); }
                body.putIfAbsent("shotSize",TextNode.valueOf("MEDIUM")); body.putIfAbsent("cameraAngle",TextNode.valueOf("EYE_LEVEL")); body.putIfAbsent("cameraMovement",TextNode.valueOf("STATIC")); body.putIfAbsent("difficulty",TextNode.valueOf("B"));
                if(!Set.of("A","B","C","D").contains(text(body,"difficulty")))throw new IllegalArgumentException("镜头难度应为 A/B/C/D");
                body.putIfAbsent("startState",obj()); body.putIfAbsent("endState",obj());
                if(body.has("storyTime")&&!body.path("storyTime").isNumber())throw new IllegalArgumentException("storyTime 必须为数字");
                if(body.has("presentationOrder")&&(!body.path("presentationOrder").isIntegralNumber()||body.path("presentationOrder").asInt()<1))throw new IllegalArgumentException("presentationOrder 必须为正整数");
                String timelineType=body.path("timelineType").asText("NORMAL").toUpperCase(Locale.ROOT); if(!Set.of("NORMAL","FLASHBACK","FLASH_FORWARD","MEMORY","DREAM","HALLUCINATION","PARALLEL_TIMELINE","TIME_SKIP").contains(timelineType))throw new IllegalArgumentException("timelineType 无效"); body.put("timelineType",timelineType);
                body.putIfAbsent("cameraPlan",defaultCameraPlan());
                body.putIfAbsent("characterIds",JsonNodeFactory.instance.arrayNode()); body.putIfAbsent("propIds",JsonNodeFactory.instance.arrayNode()); body.putIfAbsent("dialogueIds",JsonNodeFactory.instance.arrayNode());
                String p=body.path("projectId").asText(""); if(p.isEmpty()) p=project(store.get(SCENE,required(body,"sceneId")));
                checkRefs(body,"characterIds",CHARACTER,p); checkRefs(body,"propIds",PROP,p);
                if(!text(body,"locationId").isBlank()) sameProject(LOCATION,text(body,"locationId"),p);
            }
            case DIALOGUE_LINE -> { required(body,"displayText"); body.putIfAbsent("dialectText",TextNode.valueOf("")); body.putIfAbsent("speechText",TextNode.valueOf("")); body.putIfAbsent("dialect",TextNode.valueOf("MANDARIN"));
                if(!text(body,"characterId").isBlank()) { String p=text(body,"projectId"); if(p.isEmpty())p=project(store.get(SHOT,required(body,"shotId"))); sameProject(CHARACTER,text(body,"characterId"),p); }
            }
            case DIALECT_CORRECTION -> { required(body,"displayText"); required(body,"dialectText"); required(body,"speechText"); required(body,"dialect"); body.put("humanConfirmed",true); }
            default -> { }
        }
    }
    private void checkRefs(JsonNode body,String field,ResourceKind kind,String projectId) {
        if(!body.path(field).isArray()) throw new IllegalArgumentException(field+" 必须是列表");
        for(JsonNode ref:body.path(field)) sameProject(kind,ref.asText(),projectId);
    }
    private ObjectNode defaultCameraPlan() {
        return obj().put("position","主行动区正前方，略偏主体视线一侧").put("height","人物胸口高度")
            .put("distance","中景工作距离").put("lensMm",50).put("horizontalAngle","正面偏左15度")
            .put("verticalAngle","水平，俯角0度").put("subjectPlacement","主体位于画面左侧三分之一")
            .put("focusPoint","主体眼睛").put("depthOfField","中等景深，背景可辨但不抢主体")
            .put("lightingDirection","画面左后方侧光").put("movementPath","固定").put("movementSpeed","无");
    }
    public void sameProject(ResourceKind kind,String id,String projectId) { if(!project(store.get(kind,id)).equals(projectId))throw new IllegalArgumentException("引用资产不属于当前项目"); }
}
