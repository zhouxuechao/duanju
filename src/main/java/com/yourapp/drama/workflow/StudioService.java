package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.production.DirectorStyleResolver;
import com.yourapp.drama.production.EditorialTiming;
import com.yourapp.drama.production.EditOperation;
import com.yourapp.drama.production.EditingEngine;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import com.yourapp.drama.production.GenerationProfilePolicy;

@Service
public class StudioService {
    private final DocumentStore store;
    private final DirectorStyleResolver directorStyles;
    private final StoryProfilePolicy storyProfiles;
    private final EpisodeFormatResolver episodeFormats;
    private final StoryFormatResolver storyFormats;
    private final SemanticDependencyResolver dependencies;
    private final StoryFactResolver storyFacts;
    private final GenerationProfilePolicy generationProfiles;
    private static final Set<ResourceKind> EDITABLE=EnumSet.of(PROJECT,SHOT,DIALOGUE_LINE,VOICE_PROFILE,VOICE_STATE,TIMELINE,TIMELINE_ITEM,STORY_FACT,STORY_FACT_MUTATION,CHARACTER_KNOWLEDGE,RELATIONSHIP,LOCATION_STATE,PROP_STATE,ENTITY_ALIAS,CHARACTER_STATE,DIALECT_DICTIONARY,DIALECT_PHRASE,DIALECT_EXAMPLE,DIALECT_CORRECTION);
    private static final Set<String> PROTECTED=Set.of("id","createdAt","updatedAt","revision","status","locked","selected","qcStatus","providerUrl","archiveUrl","generationJobId","providerRequestId","handoffStatus","providerUrlExpiresAt","identityLocked","activeStoryDocumentId","activeStoryBibleId","storyDocumentId","storyBibleId","continuitySnapshot","continuityHash","stale","assetReferencesStale","assetViewIds","directorPlanVersion","dramaticBeatVersion","shotPlanVersion","directorPlanSnapshot","dramaticBeatSnapshot","shotPlanSnapshot","contentRevision","previewTimelineRevision","previewStale","impactDecision","reasonCodes");
    public StudioService(DocumentStore store,DirectorStyleResolver directorStyles,StoryProfilePolicy storyProfiles,EpisodeFormatResolver episodeFormats,StoryFormatResolver storyFormats,SemanticDependencyResolver dependencies,StoryFactResolver storyFacts,GenerationProfilePolicy generationProfiles) { this.store=store;this.directorStyles=directorStyles;this.storyProfiles=storyProfiles;this.episodeFormats=episodeFormats;this.storyFormats=storyFormats;this.dependencies=dependencies;this.storyFacts=storyFacts;this.generationProfiles=generationProfiles; }
    public List<ObjectNode> list(ResourceKind kind,String projectId,String parentId) { return store.list(kind,projectId,parentId); }
    public ObjectNode get(ResourceKind kind,String id) { return store.get(kind,id); }
    public ObjectNode transitionCharacterKnowledge(ObjectNode request) {
        String projectId=required(request,"projectId"),characterId=required(request,"characterId"),factId=required(request,"factId");
        String state=required(request,"newKnowledgeState").toUpperCase(Locale.ROOT),reason=required(request,"reason");
        String sourceSceneId=required(request,"sourceSceneId"),sourceBeatId=required(request,"sourceBeatId");
        if(!Set.of("KNOWN","UNKNOWN","SUSPECTED","MISUNDERSTOOD").contains(state))throw new IllegalArgumentException("newKnowledgeState 只能为 KNOWN、UNKNOWN、SUSPECTED 或 MISUNDERSTOOD");
        if(!request.path("effectiveFromStoryTime").isNumber())throw new IllegalArgumentException("effectiveFromStoryTime 必须为数字");
        if("MISUNDERSTOOD".equals(state)&&text(request,"believedStatement").isBlank())throw new IllegalArgumentException("MISUNDERSTOOD 必须包含 believedStatement");
        double effectiveFrom=request.path("effectiveFromStoryTime").asDouble();
        return store.transaction(()->{
            store.getForUpdate(PROJECT,projectId);
            sameProject(CHARACTER,characterId,projectId);sameProject(STORY_FACT,factId,projectId);
            ObjectNode scene=store.get(SCENE,sourceSceneId),beat=store.get(BEAT,sourceBeatId);
            if(!project(scene).equals(projectId)||!project(beat).equals(projectId)||!sourceSceneId.equals(text(beat,"sceneId")))throw new IllegalArgumentException("知识迁移的 sourceSceneId/sourceBeatId 归属不一致");
            List<ObjectNode> history=store.list(CHARACTER_KNOWLEDGE,projectId,null).stream()
                    .filter(value->characterId.equals(text(value,"characterId"))&&factId.equals(text(value,"factId")))
                    .sorted(Comparator.comparingDouble(this::knowledgeFrom)).toList();
            for(ObjectNode existing:history)if(Double.compare(knowledgeFrom(existing),effectiveFrom)==0)throw new WorkflowException("KNOWLEDGE_TIME_CONFLICT","同一角色对同一事实在同一故事时间只能有一个知识状态");
            ObjectNode previous=history.isEmpty()?null:history.get(history.size()-1);
            if(previous!=null&&effectiveFrom<knowledgeFrom(previous))throw new WorkflowException("KNOWLEDGE_TRANSITION_OUT_OF_ORDER","角色知识迁移不能写入最新状态之前的故事时间");
            if(previous!=null&&previous.path("validToStoryTime").isNumber()&&effectiveFrom<previous.path("validToStoryTime").asDouble())throw new WorkflowException("KNOWLEDGE_TRANSITION_OUT_OF_ORDER","角色知识迁移不能落入已有历史区间");
            if(previous!=null&&!previous.path("validToStoryTime").isNumber())store.closeCharacterKnowledgeInterval(id(previous),revision(previous),effectiveFrom);
            ObjectNode next=obj().put("projectId",projectId).put("characterId",characterId).put("factId",factId)
                    .put("knowledgeState",state).put("validFromStoryTime",effectiveFrom).put("knownFromStoryTime",effectiveFrom)
                    .put("transitionReason",reason).put("sourceSceneId",sourceSceneId).put("sourceBeatId",sourceBeatId);
            if(previous!=null)next.put("previousKnowledgeId",id(previous));
            if(request.hasNonNull("believedStatement"))next.set("believedStatement",request.path("believedStatement").deepCopy());
            validate(CHARACTER_KNOWLEDGE,next);validateKnowledgeInterval(next);
            return store.create(CHARACTER_KNOWLEDGE,next);
        });
    }
    public ObjectNode create(ResourceKind kind,ObjectNode request) {
        editable(kind);
        ObjectNode body=request.deepCopy(); PROTECTED.forEach(body::remove);
        validate(kind,body);
        if(kind==CHARACTER_KNOWLEDGE)return store.transaction(()->{validateKnowledgeInterval(body);return store.create(kind,body);});
        if(Set.of(CHARACTER_STATE,LOCATION_STATE,PROP_STATE,VOICE_STATE,RELATIONSHIP).contains(kind))return store.transaction(()->{validateTemporalInterval(kind,body);return store.create(kind,body);});
        if(kind==STORY_FACT_MUTATION)return store.transaction(()->{ObjectNode fact=store.getForUpdate(STORY_FACT,required(body,"factId"));String projectId=required(body,"projectId");if(!project(fact).equals(projectId))throw new IllegalArgumentException("事实变更与事实不属于同一项目");ObjectNode scene=store.get(SCENE,required(body,"sceneId")),beat=store.get(BEAT,required(body,"beatId"));if(!project(scene).equals(projectId)||!project(beat).equals(projectId)||!id(scene).equals(text(beat,"sceneId")))throw new IllegalArgumentException("事实变更的 sceneId/beatId 归属不一致");double at=body.path("effectiveFromStoryTime").asDouble();for(ObjectNode existing:store.list(STORY_FACT_MUTATION,projectId,id(fact)))if(Double.compare(existing.path("effectiveFromStoryTime").asDouble(),at)==0)throw new WorkflowException("FACT_MUTATION_TIME_CONFLICT","同一事实在同一故事时间只能有一个变更");ObjectNode expected=factAudit(storyFacts.resolveFactBefore(id(fact),at));if(!expected.equals(body.path("before")))throw new WorkflowException("FACT_MUTATION_BEFORE_MISMATCH","before 与该故事时间之前的事实版本不一致");return store.create(kind,body);});
        if (kind==SHOT) {
            body.put("status","PLANNED");
            body.putArray("statusHistory").add(obj().put("to","PLANNED").put("at",java.time.Instant.now().toString()));
            return store.transaction(()->{store.getForUpdate(SCENE,required(body,"sceneId"));body.putIfAbsent("shotNo",IntNode.valueOf(store.list(SHOT,null,required(body,"sceneId")).size()+1));body.putIfAbsent("presentationOrder",body.path("shotNo"));return store.create(kind,body);});
        }
        if (kind==PROJECT) {
            body.put("status","IDEA");
            String legacySource=body.path("storyProfile").path("sourceMode").asText();
            body.putIfAbsent("sourceMode",com.fasterxml.jackson.databind.node.TextNode.valueOf(Set.of("NOVEL","SCRIPT").contains(legacySource)?legacySource:"IDEA"));
        }
        if (kind==CHARACTER) body.put("identityLocked",false);
        return store.create(kind,body);
    }
    public ObjectNode update(ResourceKind kind,String id,ObjectNode request) {
        editable(kind);
        if(kind==STORY_FACT)throw new WorkflowException("STORY_FACT_IMMUTABLE","故事事实不能原地覆盖，请创建带生效故事时间的 StoryFactMutation");
        if(kind==STORY_FACT_MUTATION)throw new WorkflowException("STORY_FACT_MUTATION_IMMUTABLE","事实变更记录不可修改，请追加新的变更记录");
        if(kind==CHARACTER_KNOWLEDGE)throw new WorkflowException("CHARACTER_KNOWLEDGE_IMMUTABLE","角色知识是故事时间版本，请追加新状态，不能覆盖历史");
        long expected=request.path("revision").asLong(-1);
        if(expected<1) throw new IllegalArgumentException("修改必须包含当前 revision，避免覆盖其他修改");
        return store.transaction(() -> {
            ObjectNode current=store.getForUpdate(kind,id);
            if(kind==TIMELINE_ITEM&&store.get(TIMELINE,required(current,"timelineId")).path("locked").asBoolean())throw new WorkflowException("TIMELINE_LOCKED","已锁定的时间线不能修改剪辑点");
            if(current.path("locked").asBoolean() || current.path("identityLocked").asBoolean()) throw new WorkflowException("LOCKED","已锁定的资产不能直接修改，请创建新版本");
            if(kind==SHOT && !Set.of("DRAFT","PLANNED","NEEDS_REPAIR","FAILED","PROVIDER_URL_EXPIRED").contains(text(current,"status")))
                throw new WorkflowException("SHOT_IN_PRODUCTION","镜头已有生产结果，请先发起修改，再生成新版本");
            ObjectNode patch=request.deepCopy(); PROTECTED.forEach(patch::remove);
            if(patch.has("projectId") && !text(patch,"projectId").equals(text(current,"projectId"))) throw new IllegalArgumentException("不能把记录移到另一项目");
            if(kind.parentField()!=null && patch.has(kind.parentField()) && !text(patch,kind.parentField()).equals(text(current,kind.parentField()))) throw new IllegalArgumentException("不能修改所属对象");
            ObjectNode next=merge(current,patch);if(kind==TIMELINE_ITEM)prepareClipReplacement(current,next);ObjectNode impact=dependencies.resolve(kind.path(),id,current,next);next.set("impactDecision",impact);if(kind==TIMELINE)next.put("contentRevision",current.path("contentRevision").asLong(1)+1).put("previewStale",true).put("timelineQaStatus","PENDING");validate(kind,next);
            ObjectNode saved=store.update(kind,id,expected,next);
            if(!impact.path("changedFields").isEmpty()){ObjectNode feedback=obj().put("projectId",project(saved)).put("targetKind",kind.path()).put("targetId",id).put("fieldPath",String.join(",",impact.path("changedFields").valueStream().map(JsonNode::asText).toList())).put("reviewer","HUMAN").put("feedbackKind","SEMANTIC_EDIT").put("freeformNote",patch.path("feedbackNote").asText(""));feedback.set("beforeTextOrJson",current.deepCopy());feedback.set("afterTextOrJson",saved.deepCopy());feedback.set("versionContext",obj().put("sourceRevision",revision(current)).put("resultRevision",revision(saved)));feedback.set("impactDecision",impact.deepCopy());feedback.set("reasonCodes",patch.path("reasonCodes").isArray()?patch.path("reasonCodes").deepCopy():JsonNodeFactory.instance.arrayNode());store.create(HUMAN_EDIT_FEEDBACK,feedback);}
            if(kind==TIMELINE_ITEM)reflowTimeline(current,saved);
            return saved;
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
            case PROJECT -> { required(body,"name");String sourceMode=body.path("sourceMode").asText("IDEA").toUpperCase(Locale.ROOT);if(!Set.of("IDEA","NOVEL","SCRIPT").contains(sourceMode))throw new IllegalArgumentException("sourceMode 只支持 IDEA、NOVEL、SCRIPT");body.put("sourceMode",sourceMode);if("IDEA".equals(sourceMode))required(body,"idea");else body.putIfAbsent("idea",TextNode.valueOf("NOVEL".equals(sourceMode)?"等待上传小说":"等待上传剧本"));
                if(body.has("episodeCount")&&!body.path("episodeCount").isIntegralNumber())throw new IllegalArgumentException("episodeCount 必须为整数");
                int count=body.path("episodeCount").asInt(1); if(count<1||count>100)throw new IllegalArgumentException("集数应为 1 到 100");
                body.put("episodeCount",count); body.putIfAbsent("targetDuration",IntNode.valueOf(20)); body.putIfAbsent("ratio",TextNode.valueOf("9:16")); body.putIfAbsent("style",TextNode.valueOf("写实电影")); body.putIfAbsent("dialect",TextNode.valueOf("MANDARIN"));
                if(body.has("directorStyleProfile")&&!body.path("directorStyleProfile").isObject())throw new IllegalArgumentException("directorStyleProfile 必须为对象");
                body.set("directorStyleProfile",directorStyles.resolve(body,obj()));
                if(!body.path("targetDuration").isNumber())throw new IllegalArgumentException("targetDuration 必须为数字");
                if(body.path("targetDuration").asDouble()<10||body.path("targetDuration").asDouble()>1800)throw new IllegalArgumentException("单集时长应为 10 到 1800 秒");
                ObjectNode enriched=storyProfiles.enrich(body); body.removeAll(); body.setAll(enriched);
                body.set("episodeFormat",episodeFormats.resolve(body));
                body.set("storyFormat",storyFormats.resolve(body));
                ObjectNode configured=generationProfiles.applyDefaults(body);body.removeAll();body.setAll(configured);
            }
            case EPISODE, SCENE, LOCATION, PROP, VOICE_PROFILE -> required(body,"name");
            case STORY_FACT -> { required(body,"factKey"); required(body,"predicate"); required(body,"statement"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字");String role=text(body,"narrativeRole");if(!role.isBlank()&&!Set.of("KNOWN_FACT","HIDDEN_TRUTH","FORESHADOWING","PAYOFF","REVEAL").contains(role))throw new IllegalArgumentException("narrativeRole 无效");body.putIfAbsent("status", TextNode.valueOf("ACTIVE")); }
            case STORY_FACT_MUTATION -> { required(body,"factId");if(!body.path("before").isObject()||!body.path("after").isObject()||text(body,"reason").isBlank()||text(body,"sceneId").isBlank()||text(body,"beatId").isBlank())throw new IllegalArgumentException("StoryFactMutation 必须同时包含 before、after、reason、sceneId、beatId");String operation=required(body,"operation").toUpperCase(Locale.ROOT);if(!Set.of("REVISE","RETRACT","REACTIVATE").contains(operation))throw new IllegalArgumentException("operation 必须为 REVISE、RETRACT 或 REACTIVATE");if(!body.path("effectiveFromStoryTime").isNumber())throw new IllegalArgumentException("effectiveFromStoryTime 必须为数字");for(String field:List.of("statement","predicate","status"))if(!body.path("before").has(field)||!body.path("after").has(field))throw new IllegalArgumentException("before/after 必须包含 statement、predicate、status");body.put("operation",operation); }
            case CHARACTER_KNOWLEDGE -> { required(body,"characterId"); required(body,"factId"); required(body,"knowledgeState"); if(!Set.of("KNOWN","UNKNOWN","SUSPECTED","MISUNDERSTOOD").contains(text(body,"knowledgeState")))throw new IllegalArgumentException("knowledgeState 只能为 KNOWN、UNKNOWN、SUSPECTED 或 MISUNDERSTOOD");if("MISUNDERSTOOD".equals(text(body,"knowledgeState")))required(body,"believedStatement");if(!body.path("validFromStoryTime").isNumber()&&body.path("knownFromStoryTime").isNumber())body.set("validFromStoryTime",body.path("knownFromStoryTime"));if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字");body.set("knownFromStoryTime",body.path("validFromStoryTime"));if(body.path("validToStoryTime").isNumber()&&body.path("validToStoryTime").asDouble()<=body.path("validFromStoryTime").asDouble())throw new IllegalArgumentException("validToStoryTime 必须晚于 validFromStoryTime"); }
            case RELATIONSHIP -> { required(body,"subjectCharacterId"); required(body,"objectCharacterId"); required(body,"relationshipType"); required(body,"state"); if(text(body,"subjectCharacterId").equals(text(body,"objectCharacterId")))throw new IllegalArgumentException("人物关系不能连接同一人物"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("人物关系必须包含 projectId"); sameProject(CHARACTER,text(body,"subjectCharacterId"),p); sameProject(CHARACTER,text(body,"objectCharacterId"),p); }
            case LOCATION_STATE -> { required(body,"locationId"); required(body,"state"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("地点状态必须包含 projectId"); sameProject(LOCATION,text(body,"locationId"),p); }
            case CHARACTER_STATE -> { required(body,"characterId"); required(body,"state"); if(!body.path("alive").isBoolean())throw new IllegalArgumentException("alive 必须为布尔值"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("人物状态必须包含 projectId"); sameProject(CHARACTER,text(body,"characterId"),p); body.putIfAbsent("injuries",JsonNodeFactory.instance.arrayNode());body.putIfAbsent("carriedProps",JsonNodeFactory.instance.arrayNode()); }
            case PROP_STATE -> { required(body,"propId"); required(body,"state"); required(body,"condition"); if(!body.path("visible").isBoolean())throw new IllegalArgumentException("visible 必须为布尔值"); if(!body.path("validFromStoryTime").isNumber())throw new IllegalArgumentException("validFromStoryTime 必须为数字"); String p=text(body,"projectId"); if(p.isBlank())throw new IllegalArgumentException("道具状态必须包含 projectId"); sameProject(PROP,text(body,"propId"),p); String hand=text(body,"heldByHand");if(!hand.isBlank()&&!Set.of("LEFT","RIGHT","BOTH","NONE").contains(hand))throw new IllegalArgumentException("heldByHand 必须为 LEFT/RIGHT/BOTH/NONE"); }
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
                double duration=body.path("duration").asDouble(3); if(!Double.isFinite(duration)||duration<EditorialTiming.MIN_SHOT_SECONDS)throw new IllegalArgumentException("镜头时长至少为 "+EditorialTiming.MIN_SHOT_SECONDS+" 秒，才能进入当前剪辑链"); body.put("duration",duration);
                try { ShotRelation.valueOf(required(body,"relationToPrevious")); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("shot.relationToPrevious 不在允许值中："+Arrays.toString(ShotRelation.values())); }
                String timeRelation=body.path("timeRelationToPrevious").asText("TIME_JUMP".equals(text(body,"relationToPrevious"))?"TIME_JUMP":"CONTINUOUS");
                if(!Set.of("CONTINUOUS","MINUTES_LATER","HOURS_LATER","NEXT_DAY","TIME_JUMP","FLASHBACK","DREAM").contains(timeRelation))throw new IllegalArgumentException("timeRelationToPrevious 无效");
                if("CONTINUOUS".equals(text(body,"relationToPrevious"))&&!"CONTINUOUS".equals(timeRelation))throw new IllegalArgumentException("连续镜头的故事时间关系必须为 CONTINUOUS");
                body.put("timeRelationToPrevious",timeRelation);
                body.putIfAbsent("shotSize",TextNode.valueOf("MEDIUM")); body.putIfAbsent("cameraAngle",TextNode.valueOf("EYE_LEVEL")); body.putIfAbsent("cameraMovement",TextNode.valueOf("STATIC")); body.putIfAbsent("difficulty",TextNode.valueOf("B"));
                if(!Set.of("A","B","C","D").contains(text(body,"difficulty")))throw new IllegalArgumentException("镜头难度应为 A/B/C/D");
                body.putIfAbsent("startState",obj()); body.putIfAbsent("endState",obj());
                if(body.has("storyTime")&&!body.path("storyTime").isNumber())throw new IllegalArgumentException("storyTime 必须为数字");
                if(body.has("presentationOrder")&&(!body.path("presentationOrder").isIntegralNumber()||body.path("presentationOrder").asInt()<1))throw new IllegalArgumentException("presentationOrder 必须为正整数");
                String timelineType=body.path("timelineType").asText("NORMAL").toUpperCase(Locale.ROOT); if(!Set.of("NORMAL","FLASHBACK","FLASH_FORWARD","MEMORY","DREAM","HALLUCINATION","PARALLEL_TIMELINE","TIME_SKIP").contains(timelineType))throw new IllegalArgumentException("timelineType 无效"); body.put("timelineType",timelineType);
                body.putIfAbsent("cameraPlan",defaultCameraPlan());
                body.putIfAbsent("feltIntent",TextNode.valueOf(required(body,"purpose")));
                body.putIfAbsent("endpoint",TextNode.valueOf(required(body,"action")+"完成后的稳定末态"));
                String sequenceRelation=text(body,"sequenceRelation");
                if(sequenceRelation.isBlank())body.put("sequenceRelation",deriveSequenceRelation(body));
                else if(!Set.of("SEQUENCE_FIRST_CLIP","SEAMLESS_CONTINUATION","INTENTIONAL_NEXT_SHOT","BRIDGE_BETWEEN_KNOWN_STATES","REPAIR_TAIL","REANCHOR_AFTER_DRIFT").contains(sequenceRelation))throw new IllegalArgumentException("shot.sequenceRelation 无效");
                if(!body.has("promptCarriers"))body.set("promptCarriers",obj().putArray("performance").add(required(body,"action")));
                body.putIfAbsent("completedBeats",JsonNodeFactory.instance.arrayNode());body.putIfAbsent("reservedFutureBeats",JsonNodeFactory.instance.arrayNode());body.putIfAbsent("stateChanges",body.path("authorizedChanges").isArray()?body.path("authorizedChanges").deepCopy():JsonNodeFactory.instance.arrayNode());
                body.putIfAbsent("characterIds",JsonNodeFactory.instance.arrayNode()); body.putIfAbsent("propIds",JsonNodeFactory.instance.arrayNode()); body.putIfAbsent("dialogueIds",JsonNodeFactory.instance.arrayNode());
                String p=body.path("projectId").asText(""); if(p.isEmpty()) p=project(store.get(SCENE,required(body,"sceneId")));
                checkRefs(body,"characterIds",CHARACTER,p); checkRefs(body,"propIds",PROP,p);
                if(!text(body,"locationId").isBlank()) sameProject(LOCATION,text(body,"locationId"),p);
            }
            case DIALOGUE_LINE -> { String semantic=required(body,"semanticText"); body.putIfAbsent("subtitleText",TextNode.valueOf(semantic)); body.putIfAbsent("spokenText",TextNode.valueOf("")); body.putIfAbsent("dialect",TextNode.valueOf("MANDARIN"));
                if(!text(body,"characterId").isBlank()) { String p=text(body,"projectId"); if(p.isEmpty())p=project(store.get(SHOT,required(body,"shotId"))); sameProject(CHARACTER,text(body,"characterId"),p); }
            }
            case TIMELINE_ITEM -> {
                String track=required(body,"track").toUpperCase(Locale.ROOT);if(!Set.of("VIDEO","DIALOGUE","AMBIENCE","SFX","BGM").contains(track))throw new IllegalArgumentException("时间线轨道类型无效");
                long start=body.path("startMs").asLong(-1),duration=body.path("durationMs").asLong(-1);if(start<0||duration<=0)throw new IllegalArgumentException("时间线开始时间必须非负且时长必须大于 0");
                if("VIDEO".equals(track)){if(body.hasNonNull("linkedVideoTimelineItemId"))throw new IllegalArgumentException("VIDEO 时间线片段不能绑定另一个 VIDEO 片段");long sourceIn=body.path("sourceInMs").asLong(-1),sourceOut=body.path("sourceOutMs").asLong(-1),pauseDuration=EditingEngine.hasOperation(body,EditOperation.PAUSE)?body.path("pauseDurationMs").asLong(-1):0,sourceDuration=duration-Math.max(0,pauseDuration);if(sourceIn<0||sourceOut<=sourceIn||pauseDuration<0||sourceDuration<=0||sourceOut-sourceIn!=sourceDuration)throw new IllegalArgumentException("视频剪辑源区间必须与片段时长及停帧时长一致");ObjectNode take=store.get(VIDEO_TAKE,required(body,"videoTakeId"));long actual=take.path("actualDurationMs").asLong(0);if(actual>0&&sourceOut>actual)throw new IllegalArgumentException("视频剪辑出点超过素材实际时长");String transition=body.path("transition").asText("CUT").toUpperCase(Locale.ROOT);if(!Set.of("CUT","MATCH_CUT","CROSS_DISSOLVE","FADE_TO_BLACK").contains(transition))throw new IllegalArgumentException("镜头衔接类型无效，只支持 CUT、MATCH_CUT、CROSS_DISSOLVE、FADE_TO_BLACK");long transitionDuration=body.path("transitionDurationMs").asLong("CROSS_DISSOLVE".equals(transition)?300:0);if("CROSS_DISSOLVE".equals(transition)&&(transitionDuration<100||transitionDuration>1000||transitionDuration>=duration))throw new IllegalArgumentException("叠化时长必须为 0.1～1 秒且短于当前片段");body.put("sourceStartMs",sourceIn).put("transition",transition).put("transitionDurationMs",transitionDuration);body.set("editOperations",EditingEngine.synchronizeVideoOperations(body,actual));}
                else if(body.hasNonNull("linkedVideoTimelineItemId")){ObjectNode linked=store.get(TIMELINE_ITEM,text(body,"linkedVideoTimelineItemId"));if(!"VIDEO".equals(text(linked,"track")))throw new IllegalArgumentException("音频只能绑定 VIDEO 时间线片段");if(!text(linked,"timelineId").equals(required(body,"timelineId")))throw new IllegalArgumentException("音频与绑定画面必须属于同一时间线");if(!project(linked).equals(text(body,"projectId")))throw new IllegalArgumentException("音频与绑定画面必须属于同一项目");}
                if((EditingEngine.hasOperation(body,EditOperation.J_CUT)||EditingEngine.hasOperation(body,EditOperation.L_CUT))&&text(body,"linkedVideoTimelineItemId").isBlank())throw new IllegalArgumentException("J-Cut/L-Cut 必须绑定具体的时间线画面片段");
            }
            case DIALECT_CORRECTION -> { required(body,"semanticText"); required(body,"subtitleText"); required(body,"spokenText"); required(body,"dialect"); body.put("humanConfirmed",true); }
            default -> { }
        }
    }
    private void checkRefs(JsonNode body,String field,ResourceKind kind,String projectId) {
        if(!body.path(field).isArray()) throw new IllegalArgumentException(field+" 必须是列表");
        for(JsonNode ref:body.path(field)) sameProject(kind,ref.asText(),projectId);
    }
    private void reflowTimeline(ObjectNode before,ObjectNode saved){
        String timelineId=required(before,"timelineId");if("VIDEO".equals(text(saved,"track"))){List<ObjectNode> videos=new ArrayList<>(store.list(TIMELINE_ITEM,project(saved),timelineId).stream().filter(item->"VIDEO".equals(text(item,"track"))).toList());videos.sort(Comparator.comparingLong(item->item.path("startMs").asLong()));long cursor=0;for(int index=0;index<videos.size();index++){ObjectNode video=videos.get(index);String transition=index==0?"CUT":video.path("transition").asText("CUT");long overlap="CROSS_DISSOLVE".equals(transition)?video.path("transitionDurationMs").asLong(300):0,newStart=Math.max(0,cursor-overlap),delta=newStart-video.path("startMs").asLong();if(index==0&&!"CUT".equals(text(video,"transition")))video=store.update(TIMELINE_ITEM,text(video,"id"),revision(video),video.deepCopy().put("transition","CUT").put("transitionDurationMs",0));if(delta!=0){ObjectNode shifted=video.deepCopy().put("startMs",newStart);video=store.update(TIMELINE_ITEM,text(video,"id"),revision(video),shifted);for(ObjectNode audio:store.list(TIMELINE_ITEM,project(saved),timelineId))if(!"VIDEO".equals(text(audio,"track"))&&text(audio,"linkedVideoTimelineItemId").equals(text(video,"id")))store.update(TIMELINE_ITEM,text(audio,"id"),revision(audio),audio.deepCopy().put("startMs",audio.path("startMs").asLong()+delta));}cursor=newStart+video.path("durationMs").asLong();}ObjectNode timeline=store.getForUpdate(TIMELINE,timelineId);store.update(TIMELINE,timelineId,revision(timeline),timeline.deepCopy().put("durationMs",cursor).put("contentRevision",timeline.path("contentRevision").asLong(1)+1).put("previewStale",true).put("timelineQaStatus","PENDING"));return;}ObjectNode timeline=store.getForUpdate(TIMELINE,timelineId);store.update(TIMELINE,timelineId,revision(timeline),timeline.deepCopy().put("contentRevision",timeline.path("contentRevision").asLong(1)+1).put("previewStale",true).put("timelineQaStatus","PENDING"));
    }
    private void prepareClipReplacement(ObjectNode current,ObjectNode next){
        if(!"VIDEO".equals(text(next,"track")))return;String beforeTake=text(current,"videoTakeId"),afterTake=text(next,"videoTakeId");boolean changed=!Objects.equals(beforeTake,afterTake),declared=EditingEngine.hasOperation(next,EditOperation.CLIP_REPLACE);
        if(!changed){if(declared)throw new WorkflowException("CLIP_REPLACE_NO_CHANGE","Clip Replace 必须选择新的视频 Take");return;}
        if(!declared)throw new WorkflowException("CLIP_REPLACE_OPERATION_REQUIRED","替换视频 Take 必须声明 CLIP_REPLACE 剪辑操作");
        ObjectNode take=store.get(VIDEO_TAKE,afterTake);if(!text(take,"shotId").equals(text(current,"shotId")))throw new WorkflowException("CLIP_REPLACE_SHOT_MISMATCH","Clip Replace 只能使用同一镜头的 Take");
        if(!take.path("selected").asBoolean()||!take.path("locked").asBoolean()||!"PASSED".equals(text(take,"qcStatus")))throw new WorkflowException("CLIP_REPLACE_TAKE_NOT_APPROVED","Clip Replace 只能使用已采用、已锁定且质检通过的 Take");
        String source=text(take,"archiveUrl");if(source.isBlank())throw new WorkflowException("CLIP_REPLACE_ARCHIVE_REQUIRED","替换 Take 必须已有稳定归档地址");ObjectNode keyframe=store.get(KEYFRAME,required(take,"sourceKeyframeId"));
        ArrayNode history=next.path("replacementHistory").isArray()?(ArrayNode)next.path("replacementHistory").deepCopy():JsonNodeFactory.instance.arrayNode();history.add(obj().put("fromVideoTakeId",beforeTake).put("toVideoTakeId",afterTake).put("at",java.time.Instant.now().toString()));next.set("replacementHistory",history);next.put("replacedVideoTakeId",beforeTake).put("sourceUrl",source);next.set("assetViewIds",keyframe.path("assetViewIds").isArray()?keyframe.path("assetViewIds").deepCopy():JsonNodeFactory.instance.arrayNode());
    }
    private ObjectNode defaultCameraPlan() {
        return obj().put("position","主行动区正前方，略偏主体视线一侧").put("height","人物胸口高度")
            .put("distance","中景工作距离").put("lensMm",50).put("horizontalAngle","正面偏左15度")
            .put("verticalAngle","水平，俯角0度").put("subjectPlacement","主体位于画面左侧三分之一")
            .put("focusPoint","主体眼睛").put("depthOfField","中等景深，背景可辨但不抢主体")
            .put("lightingDirection","画面左后方侧光").put("movementPath","固定").put("movementSpeed","无");
    }
    private ObjectNode factAudit(JsonNode fact){ObjectNode result=obj();for(String field:List.of("statement","predicate","subjectEntityId","objectEntityId","value","status","validToStoryTime","revealedAtStoryTime"))if(fact.has(field))result.set(field,fact.path(field).deepCopy());return result;}
    private String deriveSequenceRelation(JsonNode shot){
        String relation=text(shot,"relationToPrevious");
        if(shot.path("shotNo").asInt(0)<=1||"ESTABLISHING".equals(relation))return "SEQUENCE_FIRST_CLIP";
        if("CONTINUOUS".equals(relation))return "SEAMLESS_CONTINUATION";
        return "INTENTIONAL_NEXT_SHOT";
    }
    private void validateKnowledgeInterval(ObjectNode body){double from=body.path("validFromStoryTime").asDouble(),to=body.path("validToStoryTime").isNumber()?body.path("validToStoryTime").asDouble():Double.POSITIVE_INFINITY;for(ObjectNode existing:store.list(CHARACTER_KNOWLEDGE,required(body,"projectId"),null)){if(!text(existing,"characterId").equals(text(body,"characterId"))||!text(existing,"factId").equals(text(body,"factId")))continue;double otherFrom=existing.path("validFromStoryTime").isNumber()?existing.path("validFromStoryTime").asDouble():existing.path("knownFromStoryTime").asDouble(),otherTo=existing.path("validToStoryTime").isNumber()?existing.path("validToStoryTime").asDouble():Double.POSITIVE_INFINITY;if(from<otherTo&&otherFrom<to)throw new WorkflowException("KNOWLEDGE_TIME_CONFLICT","同一角色对同一事实的知识时间区间不能重叠");}}
    private double knowledgeFrom(JsonNode knowledge){return knowledge.path("validFromStoryTime").isNumber()?knowledge.path("validFromStoryTime").asDouble():knowledge.path("knownFromStoryTime").asDouble();}
    private void validateTemporalInterval(ResourceKind kind,ObjectNode body){
        double from=body.path("validFromStoryTime").asDouble(),to=body.path("validToStoryTime").isNumber()?body.path("validToStoryTime").asDouble():Double.POSITIVE_INFINITY;if(to<=from)throw new IllegalArgumentException("validToStoryTime 必须晚于 validFromStoryTime");
        List<String> keys=switch(kind){case CHARACTER_STATE->List.of("characterId");case LOCATION_STATE->List.of("locationId");case PROP_STATE->List.of("propId");case VOICE_STATE->List.of("voiceProfileId");case RELATIONSHIP->List.of("subjectCharacterId","objectCharacterId","relationshipType");default->throw new IllegalArgumentException("非时间版本资源");};
        for(ObjectNode existing:store.list(kind,required(body,"projectId"),null)){if(keys.stream().anyMatch(key->!text(existing,key).equals(text(body,key))))continue;double otherFrom=existing.path("validFromStoryTime").asDouble(),otherTo=existing.path("validToStoryTime").isNumber()?existing.path("validToStoryTime").asDouble():Double.POSITIVE_INFINITY;if(from<otherTo&&otherFrom<to)throw new WorkflowException("TEMPORAL_STATE_CONFLICT","同一实体的故事时间区间不能重叠");}
    }
    public void sameProject(ResourceKind kind,String id,String projectId) { if(!project(store.get(kind,id)).equals(projectId))throw new IllegalArgumentException("引用资产不属于当前项目"); }
}
