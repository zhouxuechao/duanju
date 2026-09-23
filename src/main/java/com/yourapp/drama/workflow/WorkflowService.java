package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.ProductionService;
import com.yourapp.drama.production.DirectorStyleResolver;
import com.yourapp.drama.production.VideoRequestPlanner;
import com.yourapp.drama.production.KeyframeSemanticRole;
import com.yourapp.drama.production.MediaPurpose;
import com.yourapp.drama.production.ProviderFrameMode;
import com.yourapp.drama.production.ContinuityCompatibilityEvaluator;
import com.yourapp.drama.production.AssetDependencyAnalyzer;
import com.yourapp.drama.production.GenerationProfilePolicy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class WorkflowService {
    private final DocumentStore store;
    private final JobService jobs;
    private final ProductionService production;
    private final StoryDevelopmentService development;
    private final AssetViewService assetViews;
    private final StoryFactResolver factResolver;
    private final RelationshipResolver relationshipResolver;
    private final LocationStateResolver locationStateResolver;
    private final PropStateResolver propStateResolver;
    private final ContextResolver contextResolver;
    private final CharacterStateResolver characterStateResolver;
    private final QualityDiagnosisService diagnoses;
    private final DirectorStyleResolver directorStyles;
    private final EpisodeFormatResolver episodeFormats;
    private final VideoRequestPlanner videoPlanner;
    private final ContinuityCompatibilityEvaluator continuityCompatibility;
    private final GenerationProfilePolicy generationProfiles;
    public WorkflowService(DocumentStore store,JobService jobs,ProductionService production,StoryDevelopmentService development,AssetViewService assetViews,StoryFactResolver factResolver,RelationshipResolver relationshipResolver,LocationStateResolver locationStateResolver,CharacterStateResolver characterStateResolver,PropStateResolver propStateResolver,QualityDiagnosisService diagnoses,DirectorStyleResolver directorStyles,EpisodeFormatResolver episodeFormats,VideoRequestPlanner videoPlanner,ContinuityCompatibilityEvaluator continuityCompatibility,GenerationProfilePolicy generationProfiles){this.store=store;this.jobs=jobs;this.production=production;this.development=development;this.assetViews=assetViews;this.factResolver=factResolver;this.relationshipResolver=relationshipResolver;this.locationStateResolver=locationStateResolver;this.characterStateResolver=characterStateResolver;this.propStateResolver=propStateResolver;this.diagnoses=diagnoses;this.directorStyles=directorStyles;this.episodeFormats=episodeFormats;this.videoPlanner=videoPlanner;this.continuityCompatibility=continuityCompatibility;this.generationProfiles=generationProfiles;this.contextResolver=new ContextResolver();}
    public ObjectNode story(String projectId,ObjectNode body){
        ObjectNode doc=development.start(projectId,body);return doc.hasNonNull("generationJobId")?store.get(GENERATION_JOB,text(doc,"generationJobId")):doc;
    }
    public ObjectNode plan(String sceneId,ObjectNode body){
        return store.transaction(()->{
        ObjectNode first=store.get(SCENE,sceneId);store.getForUpdate(PROJECT,project(first));
        ObjectNode scene=store.getForUpdate(SCENE,sceneId), input=obj();ObjectNode episode=store.get(EPISODE,required(scene,"episodeId"));
        if(scene.path("stale").asBoolean())throw new WorkflowException("STALE_SCENE","此场景已被新的确认剧本替代，请从当前剧本进入分镜生产");
        input.set("continuitySnapshot",development.approvedSnapshot(episode));input.put("continuityHash",required(episode,"continuityHash"));input.set("scene",scene);input.set("episodeScript",episode);ObjectNode projectDocument=store.get(PROJECT,project(scene)),catalog=assets(project(scene));
        JsonNode script=store.get(STORY_DOCUMENT,required(episode,"storyDocumentId")).path("content");
        Map<String,Set<String>> scriptKeys=new HashMap<>();for(String field:List.of("characterKeys","locationKeys","propKeys")){Set<String> keys=new HashSet<>();script.path(field).forEach(v->keys.add(v.asText()));scriptKeys.put(field,keys);}
        Set<String> actors=new HashSet<>();catalog.path("characters").forEach(c->{if(scriptKeys.get("characterKeys").contains(text(c,"characterKey")))actors.add(id(c));});List<String> needed=new ArrayList<>();
        catalog.path("looks").forEach(l->{if(actors.contains(text(l,"characterId")))needed.add(id(l));});
        for(String field:List.of("locations","props"))catalog.path(field).forEach(a->{if(scriptKeys.get(field.equals("locations")?"locationKeys":"propKeys").contains(text(a,field.equals("locations")?"locationKey":"propKey")))needed.add(id(a));});
        double averageShotLength=directorStyles.resolve(projectDocument,scene).path("averageShotLength").asDouble(3);int estimatedShots=Math.max(1,(int)Math.ceil(scene.path("duration").asDouble(3)/Math.max(2,averageShotLength)));
        AssetDependencyAnalyzer.Level assetDependency=new AssetDependencyAnalyzer().classify(actors.size(),scriptKeys.get("locationKeys").size(),estimatedShots,estimatedShots>1,projectDocument.path("episodeCount").asInt(1)>=30);
        assetViews.requireReady(project(scene),required(episode,"storyBibleId"),needed,assetDependency);catalog=assets(project(scene),assetDependency);
        input.set("directorRuleProfile",obj().put("assetDependency",assetDependency.name()));
        Set<String> neededIds=new HashSet<>(needed);
        for(String field:List.of("characters","looks","locations","props")){
            ArrayNode selected=JsonNodeFactory.instance.arrayNode();
            for(JsonNode item:catalog.path(field))if((field.equals("characters")?actors:neededIds).contains(id(item))){
                ObjectNode value=compactDirectorAsset(field,item);
                if(value.has("approvedViews")){ArrayNode views=JsonNodeFactory.instance.arrayNode();
                    for(JsonNode view:value.path("approvedViews")){ObjectNode compact=obj();for(String property:List.of("id","view","setVersion","approved","stale"))if(view.has(property))compact.set(property,view.get(property));views.add(compact);}value.set("approvedViews",views);}
                selected.add(value);
            }catalog.set(field,selected);
        }
        input.set("assets",catalog);
        ArrayNode pinnedViews=input.putArray("assetViewIds");for(String assetId:needed)assetViews.approvedReferences(project(scene),required(episode,"storyBibleId"),assetId,assetDependency).forEach(v->pinnedViews.add(id(v)));
        ObjectNode styleScene=scene.deepCopy();if(body.path("directorStyleOverride").isObject())styleScene.set("directorStyleOverride",body.path("directorStyleOverride").deepCopy());
        ObjectNode plannedScene=scene.deepCopy();plannedScene.set("sceneContinuityPolicy",resolvedSceneContinuityPolicy(scene,projectDocument));input.set("scene",plannedScene);
        ObjectNode projectContext=obj();for(String field:List.of("id","name","ratio","style","dialect","sceneContinuityPolicy"))if(projectDocument.has(field))projectContext.set(field,projectDocument.get(field).deepCopy());
        input.set("project",projectContext);input.set("episodeFormat",episodeFormats.resolve(projectDocument));input.put("sceneTargetDurationSeconds",scene.path("duration").asDouble());input.set("directorStyleProfile",directorStyles.resolve(projectDocument,styleScene));
        String signature=shotPlanSignature(input);input.put("planningSignature",signature);
        ObjectNode recoverable=null;List<ObjectNode> projectJobs=store.list(GENERATION_JOB,project(scene),null);
        for(ObjectNode old:projectJobs)if("DIRECTOR_PLAN".equals(text(old,"type"))&&sceneId.equals(text(old.path("inputSnapshot").path("scene"),"id"))&&signature.equals(text(old.path("inputSnapshot"),"planningSignature"))){
            boolean active=id(old).equals(text(scene,"activeDirectorPlanJobId"));
            boolean failedDetail=projectJobs.stream().anyMatch(child->"SHOT_DETAIL".equals(text(child,"type"))&&id(old).equals(text(child.path("inputSnapshot"),"rootPlanJobId"))&&"FAILED".equals(text(child,"status")));
            if("SUCCESS".equals(text(old,"status"))&&active&&!old.path("outputSnapshot").path("detailsComplete").asBoolean()&&failedDetail){recoverable=old;continue;}
            if(Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCESS").contains(text(old,"status"))&&active)return old;
            if(old.path("submissionUncertain").asBoolean())throw new WorkflowException("SUBMISSION_UNCERTAIN","这一版本的拆镜请求状态尚未查明，请先核对已有任务的服务商记录，避免重复提交");
            if("FAILED".equals(text(old,"status"))&&active)recoverable=old;
        }
        if(recoverable!=null){
            input.put("retryOfJobId",id(recoverable));
            boolean hasValidatedProviderOutput=recoverable.path("providerOutput").isObject()&&!recoverable.path("providerOutput").isEmpty();
            if(hasValidatedProviderOutput&&!recoverable.path("reusedValidatedProviderOutput").asBoolean(false))input.put("reuseProviderOutput",true);
            else input.set("retryFeedback",obj().put("failureCode",text(recoverable,"failureCode")).put("failureReason",text(recoverable,"failureReason")).put("providerRequestId",text(recoverable,"providerRequestId")));
        }
        int nextPlanVersion=scene.path("shotPlanVersion").asInt()+1;input.put("planVersion",nextPlanVersion).put("directorPlanVersion",nextPlanVersion).put("dramaticBeatVersion",nextPlanVersion).put("shotPlanVersion",nextPlanVersion);
        ObjectNode job=jobs.enqueue(project(scene),null,"DIRECTOR_PLAN",input,"director-plan:"+signature+":"+key(body));
        for(ObjectNode old:projectJobs)if(!id(old).equals(id(job))&&"DIRECTOR_PLAN".equals(text(old,"type"))&&sceneId.equals(text(old.path("inputSnapshot").path("scene"),"id"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(old,"status")))jobs.cancel(id(old));
        retireSceneShots(scene,id(job));
        store.update(SCENE,sceneId,revision(scene),scene.deepCopy().put("activeDirectorPlanJobId",id(job)).put("activeDirectorPlanSignature",signature)
            .put("directorPlanVersion",nextPlanVersion).put("dramaticBeatVersion",nextPlanVersion).put("shotPlanVersion",nextPlanVersion));
        return job;
        });
    }
    private String shotPlanSignature(ObjectNode input){
        ObjectNode source=obj().put("sceneId",required(input.path("scene"),"id")).put("storyDocumentId",required(input.path("episodeScript"),"storyDocumentId")).put("continuityHash",required(input,"continuityHash"));
        ObjectNode scene=(ObjectNode)input.path("scene").deepCopy();scene.remove(List.of("revision","createdAt","updatedAt","activeDirectorPlanJobId","activeDirectorPlanSignature","directorPlanVersion","dramaticBeatVersion","shotPlanVersion","directorPlanStatus","shotCount"));source.set("scene",scene);
        SortedSet<String> ids=new TreeSet<>();input.path("assetViewIds").forEach(v->ids.add(v.asText()));ArrayNode refs=source.putArray("assetViewIds");ids.forEach(refs::add);
        ObjectNode settings=source.putObject("settings");for(String field:List.of("ratio","style","dialect"))settings.put(field,text(input.path("project"),field));
        if(input.path("project").path("sceneContinuityPolicy").isObject())settings.set("sceneContinuityPolicy",input.path("project").path("sceneContinuityPolicy").deepCopy());
        source.set("directorStyleProfile",input.path("directorStyleProfile").deepCopy());
        source.set("episodeFormat",input.path("episodeFormat").deepCopy());
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}
    }
    private ObjectNode compactDirectorAsset(String kind,JsonNode source){
        ObjectNode value=obj();List<String> fields=switch(kind){
            case "characters"->List.of("id","name","characterKey","description","baseLookId","identityTraits","currentState","voiceBinding","approvedViews");
            case "looks"->List.of("id","characterId","name","lookKey","description","visualPrompt","approvedViews");
            case "locations"->List.of("id","name","locationKey","description","locationBible","currentState","approvedViews");
            default->List.of("id","name","propKey","description","state","propBible","currentState","approvedViews");
        };for(String field:fields)if(source.has(field))value.set(field,source.get(field).deepCopy());return value;
    }
    private void retireSceneShots(ObjectNode scene,String replacementJobId){
        Set<String> ids=new HashSet<>();for(ObjectNode shot:store.list(SHOT,project(scene),id(scene)))if(!shot.path("stale").asBoolean()){
            ids.add(id(shot));store.update(SHOT,id(shot),revision(shot),shot.deepCopy().put("stale",true).put("supersededByPlanningJobId",replacementJobId));}
        if(ids.isEmpty())return;Set<String> timelines=new HashSet<>();
        for(ResourceKind kind:List.of(STORYBOARD,KEYFRAME,VIDEO_TAKE,DIALOGUE_LINE,AUDIO_CLIP,TIMELINE_ITEM))for(ObjectNode item:store.list(kind,project(scene),null))if(ids.contains(text(item,"shotId"))){
            if(kind==TIMELINE_ITEM)timelines.add(text(item,"timelineId"));store.update(kind,id(item),revision(item),item.deepCopy().put("stale",true).put("supersededByPlanningJobId",replacementJobId));}
        for(ObjectNode timeline:store.list(TIMELINE,project(scene),null))if(timelines.contains(id(timeline)))store.update(TIMELINE,id(timeline),revision(timeline),timeline.deepCopy().put("stale",true));
        for(ObjectNode pending:store.list(GENERATION_JOB,project(scene),null))if(ids.contains(text(pending,"shotId"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(pending,"status")))jobs.cancel(id(pending));
    }
    public ObjectNode image(String shotId,String type,ObjectNode body){
        if(!Set.of("KEYFRAME","STORYBOARD").contains(type))throw new IllegalArgumentException("图片任务类型无效");
        return store.transaction(()->{
            ObjectNode shot=store.getForUpdate(SHOT,shotId);ObjectNode existing=existingJob(project(shot),type,shotId,body);if(existing!=null)return existing; noActiveGeneration(shotId);
            MediaPurpose mediaPurpose="STORYBOARD".equals(type)?MediaPurpose.PREVIS:MediaPurpose.KEYFRAME;
            ObjectNode settings=generationProfiles.resolved(store.get(PROJECT,project(shot)));ObjectNode context=context(shot);context.put("providerMediaType","IMAGE").put("imageTaskType",mediaPurpose.name()).put("mediaPurpose",mediaPurpose.name()).put("generationProfile",text(settings,"generationProfile"));context.set("providerCapabilities",production.imageCapabilities(text(settings,"imageModel")));context.set("outputProfile",outputProfile(project(shot)));validateCharacters(context.path("assets"),shot);
            if(!text(body,"revisionFeedback").isBlank())context.put("revisionFeedback",text(body,"revisionFeedback"));
            if("KEYFRAME".equals(type))for(ObjectNode storyboard:store.list(STORYBOARD,project(shot),shotId))if(storyboard.path("selected").asBoolean()&&storyboard.path("locked").asBoolean()&&"PASSED".equals(text(storyboard,"qcStatus"))&&!storyboard.path("stale").asBoolean()){context.set("approvedStoryboard",storyboard.deepCopy());break;}
            List<ObjectNode> reviews=store.list(QC_RESULT,project(shot),null);
            for(int i=reviews.size()-1;i>=0;i--){ObjectNode review=reviews.get(i);
                if(shotId.equals(text(review,"shotId"))&&(type.equals("KEYFRAME")?"keyframes":"storyboards").equals(text(review,"targetKind"))){
                    if(!review.path("passed").asBoolean()&&text(context,"revisionFeedback").isBlank())context.put("revisionFeedback",text(review,"notes"));
                    break;
                }
            }
            if("EDIT".equals(text(body,"regenerationMode"))||!text(context,"revisionFeedback").isBlank())context.put("imageTaskType","REPAIR_EDIT");
            JsonNode compiled=production.compileImage(context);
            int version=store.list(type.equals("KEYFRAME")?KEYFRAME:STORYBOARD,project(shot),shotId).size()+1;
            ObjectNode prompt=savePrompt(shot,type,compiled,context);
            String ratio=store.get(PROJECT,project(shot)).path("ratio").asText("9:16");if(!Set.of("9:16","16:9","1:1").contains(ratio))throw new WorkflowException("UNSUPPORTED_RATIO","项目画幅必须为 9:16、16:9 或 1:1");
            ObjectNode input=obj().put("shotId",shotId).put("sceneId",required(shot,"sceneId")).put("episodeId",required(store.get(SCENE,required(shot,"sceneId")),"episodeId")).put("version",version).put("promptVersionId",id(prompt)).put("prompt",compiled.path("prompt").asText()).put("assetDependencyLevel",assetDependency(shot).name()).put("mediaPurpose",mediaPurpose.name()).put("generationProfile",text(settings,"generationProfile")).put("modelId",text(settings,"imageModel")).put("imageSize",text(settings,"imageSize")).put("ratio",ratio);copyPromptAudit(input,compiled,context);
            String semanticRole="STORYBOARD".equals(type)?KeyframeSemanticRole.STORYBOARD_GRID.name():body.path("semanticRole").asText(KeyframeSemanticRole.START_FRAME.name());KeyframeSemanticRole role=KeyframeSemanticRole.from(semanticRole);input.put("semanticRole",role.name()).put("providerFrameMode",role.nativeBoundary()?ProviderFrameMode.NATIVE_BOUNDARY.name():ProviderFrameMode.SEMANTIC_REFERENCE.name()).put("timeSec",body.path("timeSec").asDouble(role==KeyframeSemanticRole.END_FRAME?shot.path("duration").asDouble(0):0)).put("stateVersion",body.path("stateVersion").asInt(shot.path("shotPlanVersion").asInt(1)));
            input.set("context",context);
            ObjectNode imageOptions=body.path("providerOptions").isObject()?(ObjectNode)body.path("providerOptions").deepCopy():obj();
            imageOptions.put("size",text(settings,"imageSize"));
            imageOptions.putIfAbsent("watermark",BooleanNode.FALSE);
            input.set("providerOptions",imageOptions);
            ArrayNode imageRefs=input.putArray("referenceImageUrls");
            for(JsonNode reference:compiled.path("references"))if(!text(reference,"url").isBlank())imageRefs.add("COMPOSITION_REFERENCE".equals(text(reference,"role"))?text(reference,"url"):assetViews.referenceUrl(store.get(ASSET_VIEW,required(reference,"viewId"))));
            input.set("assetReferences",compiled.path("references").deepCopy());
            ArrayNode viewIds=input.putArray("assetViewIds");compiled.path("references").forEach(r->{if(r.hasNonNull("viewId"))viewIds.add(required(r,"viewId"));});
            if(context.path("approvedStoryboard").isObject())input.set("storyboardReference",context.path("approvedStoryboard").deepCopy());
            if(body.hasNonNull("regenerationMode")) input.put("regenerationMode",body.path("regenerationMode").asText());
            if(body.hasNonNull("parentKeyframeId")) input.put("parentKeyframeId",body.path("parentKeyframeId").asText());
            ObjectNode pinned=shot.deepCopy();pinned.set("assetViewIds",viewIds.deepCopy());pinned.put("assetReferencesStale",false);shot=store.update(SHOT,id(shot),revision(shot),pinned);
            if(body.hasNonNull("regenerateFromId")){
                ObjectNode old=store.get(KEYFRAME,body.path("regenerateFromId").asText()); if(!shotId.equals(text(old,"shotId")))throw new IllegalArgumentException("重画来源不属于此镜头");
                checkReferenceSnapshot(old);
                ObjectNode sourceJob=store.get(GENERATION_JOB,required(old,"generationJobId"));
                JsonNode previous=sourceJob.path("inputSnapshot");
                input.put("promptVersionId",required(previous,"promptVersionId"));input.put("prompt",required(previous,"prompt"));
                input.set("providerOptions",previous.path("providerOptions").deepCopy());input.set("referenceImageUrls",previous.path("referenceImageUrls").deepCopy());input.set("context",previous.path("context").deepCopy());
                for(String field:List.of("generationProfile","modelId","imageSize","ratio","compilerVersion","normalizedPromptHash","referenceBindingsHash","referenceAuthorityFingerprint","continuitySnapshotHash","providerCapabilitiesVersion","capabilityFingerprint","sequenceCompilerVersion"))if(previous.has(field))input.set(field,previous.path(field).deepCopy());
                // A regeneration is a new take of the same approved visual setup. Keep
                // the exact reference view/version snapshot used by the source frame;
                // the shot may have been edited since that frame was created.
                if(previous.path("assetViewIds").isArray()&&!previous.path("assetViewIds").isEmpty()){
                    input.set("assetViewIds",previous.path("assetViewIds").deepCopy());
                    input.set("assetReferences",previous.path("assetReferences").deepCopy());
                    ObjectNode repinned=shot.deepCopy();repinned.set("assetViewIds",previous.path("assetViewIds").deepCopy());repinned.put("assetReferencesStale",false);
                    shot=store.update(SHOT,id(shot),revision(shot),repinned);
                }
                input.put("regeneratedFromId",id(old));
            }
            input.set("clientRequestSnapshot",body.deepCopy());
            ObjectNode job=jobs.enqueue(project(shot),shotId,type,input,key(body));
            setShot(shot,type+"_GENERATING");return job;
        });
    }
    public ObjectNode regenerateKeyframe(String keyframeId,ObjectNode body){
        ObjectNode frame=store.get(KEYFRAME,keyframeId),request=body.deepCopy();
        preventRepeatedVisualFailure(frame,promptCompilerVersion(frame),frame.path("assetViewIds"));
        request.put("regenerateFromId",keyframeId).put("regenerationMode","REPLAY_ORIGINAL").put("parentKeyframeId",keyframeId);
        request.putIfAbsent("requestKey",TextNode.valueOf("keyframe-regenerate-"+keyframeId+"-"+UUID.randomUUID()));
        return image(required(frame,"shotId"),"KEYFRAME",request);
    }
    /** Regenerate from the current shot/assets and record explicit lineage. */
    public ObjectNode regenerateLatestKeyframe(String keyframeId,ObjectNode body){
        ObjectNode frame=store.get(KEYFRAME,keyframeId),request=body.deepCopy();
        ObjectNode shot=store.get(SHOT,required(frame,"shotId"));
        preventRepeatedVisualFailure(frame,production.imageCompilerVersion(),shot.path("assetViewIds"));
        request.put("regenerationMode","LATEST").put("parentKeyframeId",keyframeId);
        request.putIfAbsent("requestKey",TextNode.valueOf("keyframe-latest-"+keyframeId+"-"+UUID.randomUUID()));
        return image(required(frame,"shotId"),"KEYFRAME",request);
    }
    private void preventRepeatedVisualFailure(ObjectNode source,String targetCompilerVersion,JsonNode targetViewIds){
        String shotId=required(source,"shotId");
        Map<String,ObjectNode> latestReview=new HashMap<>();
        for(ObjectNode review:store.list(QC_RESULT,project(source),null))if(shotId.equals(text(review,"shotId"))&&!review.path("shadow").asBoolean())latestReview.put(text(review,"targetId"),review);
        List<ObjectNode> frames=new ArrayList<>(store.list(KEYFRAME,project(source),shotId));
        frames.sort(Comparator.comparingInt((ObjectNode f)->f.path("version").asInt()).reversed());
        String pattern=null;int consecutive=0;
        for(ObjectNode frame:frames){
            if(!targetCompilerVersion.equals(promptCompilerVersion(frame))||!sameViewSet(frame.path("assetViewIds"),targetViewIds))break;
            ObjectNode review=latestReview.get(id(frame));
            if(review==null||review.path("passed").asBoolean())break;
            String code=review.path("diagnosis").path("failureCodes").path(0).asText();
            if(code.isBlank()||(pattern!=null&&!pattern.equals(code)))break;
            pattern=code;consecutive++;
        }
        if(consecutive>=3)throw new WorkflowException("VISUAL_RETRY_LIMIT","同一视觉问题已连续 3 次未通过，请按诊断更换参考图、重做镜头设计或人工修正后再生成（"+pattern+"）");
    }
    private String promptCompilerVersion(JsonNode frame){String promptId=text(frame,"promptVersionId");if(promptId.isBlank())return "";return text(store.get(PROMPT_VERSION,promptId),"compilerVersion");}
    private boolean sameViewSet(JsonNode left,JsonNode right){Set<String>a=new LinkedHashSet<>(),b=new LinkedHashSet<>();left.forEach(v->a.add(v.asText()));right.forEach(v->b.add(v.asText()));return a.equals(b);}
    /** Apply an explicit user edit while preserving the source frame lineage. */
    public ObjectNode editGenerateKeyframe(String keyframeId,ObjectNode body){
        ObjectNode frame=store.get(KEYFRAME,keyframeId),request=body.deepCopy();
        String instruction=body.path("revisionFeedback").asText(body.path("instruction").asText(body.path("notes").asText())).trim();if(instruction.isBlank())throw new WorkflowException("EDIT_INSTRUCTION_REQUIRED","编辑生成必须说明要修正的可见偏差");
        request.put("revisionFeedback",instruction);
        request.put("regenerationMode","EDIT").put("parentKeyframeId",keyframeId);
        request.putIfAbsent("requestKey",TextNode.valueOf("keyframe-edit-"+keyframeId+"-"+UUID.randomUUID()));
        return image(required(frame,"shotId"),"KEYFRAME",request);
    }
    /** Apply the recorded diagnosis. Only repairs that cannot overwrite story truth are executed automatically. */
    public ObjectNode repairKeyframe(String keyframeId,ObjectNode body){
        ObjectNode frame=store.get(KEYFRAME,keyframeId),latest=null;
        for(ObjectNode review:store.list(QC_RESULT,project(frame),null))if(keyframeId.equals(text(review,"targetId"))&&!review.path("shadow").asBoolean())latest=review;
        if(latest==null||latest.path("passed").asBoolean())throw new WorkflowException("DIAGNOSIS_REQUIRED","关键帧没有未通过的质量诊断");
        String action=text(latest.path("diagnosis"),"recommendedRepair");
        if("RETRY_SAME_INPUT".equals(action))return regenerateKeyframe(keyframeId,body);
        if("REBUILD_PROMPT".equals(action))return regenerateLatestKeyframe(keyframeId,body);
        return obj().put("status","ACTION_REQUIRED").put("keyframeId",keyframeId).put("recommendedRepair",action)
            .put("failureOrigin",text(latest.path("diagnosis"),"failureOrigin")).set("failureCodes",latest.path("diagnosis").path("failureCodes").deepCopy());
    }
    private record VideoPreparation(ObjectNode keyframeSnapshot,ObjectNode context,JsonNode previous,ObjectNode requestPlan,JsonNode compiled){}
    private VideoPreparation prepareVideoRequest(ObjectNode keyframe,ObjectNode shot,ObjectNode body){
        ObjectNode keyframeSnapshot=compactKeyframeSnapshot(keyframe),settings=generationProfiles.resolved(store.get(PROJECT,project(shot)));
        ObjectNode context=context(shot);context.put("providerMediaType","VIDEO").put("generationProfile",text(settings,"generationProfile"));context.set("keyframe",keyframeSnapshot.deepCopy());context.set("providerCapabilities",production.videoCapabilities(text(settings,"videoModel")));context.set("videoOutputProfile",outputProfile(project(shot)).put("resolution",text(settings,"videoResolution")));attachRetake(context,shot);
        ObjectNode contextShot=(ObjectNode)context.path("shot");if(text(contextShot,"sequenceRelation").isBlank())contextShot.put("sequenceRelation",text(shot,"relationToPrevious"));
        JsonNode previous=context.path("previousTake");int maxDepth=context.path("sceneContinuityPolicy").path("maxContinuationDepth").asInt(2);if("CONTINUOUS".equals(text(shot,"relationToPrevious"))&&previous.path("continuationDepth").asInt(0)>=maxDepth){context.set("reanchorPlan",obj().put("reason","达到连续生成深度上限 "+maxDepth).put("useCanonicalReferences",true).put("source","SCENE_CONTINUITY_POLICY"));contextShot.put("sequenceRelation","REANCHOR_AFTER_DRIFT");}else if(context.path("retake").isObject())contextShot.put("sequenceRelation","REPAIR_TAIL");validateCharacters(context.path("assets"),shot);
        ObjectNode planningBody=body.deepCopy(),options=obj().put("duration",shot.path("duration").asDouble(3)).put("ratio",store.get(PROJECT,project(shot)).path("ratio").asText("9:16")).put("resolution",text(settings,"videoResolution")).put("watermark",false);
        if(body.path("providerOptions").isObject())body.path("providerOptions").fields().forEachRemaining(e->options.set(e.getKey(),e.getValue()));planningBody.set("providerOptions",options);
        JsonNode prepared=production.prepareVideo(context);ObjectNode requestPlan=videoPlanner.plan(prepared,context,keyframeSnapshot,previous,planningBody);
        ObjectNode preparedForPrompt=obj().put("strategy",prepared.path("strategy").asText());preparedForPrompt.set("references",requestPlan.path("activatedMaterials").deepCopy());context.set("preparedVideo",preparedForPrompt);context.put("videoTaskType",requestPlan.path("taskType").asText()).put("providerTaskLockMode",requestPlan.path("lockMode").asText()).put("runtimeProviderRules",requestPlan.path("runtimeRules").asText()).put("providerRulePackFingerprint",requestPlan.path("rulePackFingerprint").asText());
        JsonNode compiled=production.compileVideo(context);videoPlanner.validatePrompt(compiled.path("prompt").asText(),requestPlan.path("referenceMapping"));
        return new VideoPreparation(keyframeSnapshot,context,previous,requestPlan,compiled);
    }
    public ObjectNode videoPreview(String keyframeId,ObjectNode body){
        return store.transaction(()->{
            ObjectNode keyframe=store.get(KEYFRAME,keyframeId);checkHandoff(keyframe);ObjectNode shot=store.get(SHOT,required(keyframe,"shotId"));VideoPreparation prepared=prepareVideoRequest(keyframe,shot,body);
            ObjectNode preview=obj().put("preview",true).put("prompt",prepared.compiled().path("prompt").asText());
            for(String field:List.of("modelId","modelProfileVersion","capabilityFingerprint","taskType","lockMode","route","videoRequestRoute","activatedMaterials","excludedMaterials","referenceMapping","referenceAuthority","referenceBudget","providerParameters","rulePackFingerprint","rulePackUpstreamCommit","runtimeRuleIds","audioGenerationPolicy","modelProfile","preflight","references","firstFrameProviderUrl","resolution","ratio","desiredDuration","providerDuration","durationAdaptationReason","nativeAudio","watermark"))if(prepared.requestPlan().has(field))preview.set(field,prepared.requestPlan().path(field).deepCopy());
            ObjectNode providerRequest=preview.putObject("providerRequest");if(preview.has("firstFrameProviderUrl"))providerRequest.put("first_frame",preview.path("firstFrameProviderUrl").asText());providerRequest.set("references",preview.path("references").deepCopy());providerRequest.set("parameters",preview.path("providerParameters").deepCopy());
            return preview;
        });
    }
    public ObjectNode video(String keyframeId,ObjectNode body){
        return store.transaction(()->{
            ObjectNode keyframe=store.getForUpdate(KEYFRAME,keyframeId);
            ObjectNode existing=existingJob(project(keyframe),"VIDEO",required(keyframe,"shotId"),body);if(existing!=null)return existing;
            checkHandoff(keyframe);
            ObjectNode shot=store.getForUpdate(SHOT,required(keyframe,"shotId"));noActiveGeneration(id(shot));List<ObjectNode> existingTakes=store.list(VIDEO_TAKE,project(shot),id(shot));int maxTakes=shot.path("maxVideoTakes").asInt(store.get(PROJECT,project(shot)).path("defaultMaxVideoTakes").asInt(0));if(maxTakes>0&&existingTakes.size()>=maxTakes)throw new WorkflowException("VIDEO_TAKE_LIMIT_REACHED","本镜已达到 "+maxTakes+" 次视频生成上限，请先审查失败原因或调整预算");
            VideoPreparation prepared=prepareVideoRequest(keyframe,shot,body);ObjectNode keyframeSnapshot=prepared.keyframeSnapshot(),context=prepared.context(),requestPlan=prepared.requestPlan();JsonNode previous=prepared.previous(),compiled=prepared.compiled();
            ObjectNode prompt=savePrompt(shot,"VIDEO",compiled,context);
            int takeNo=store.list(VIDEO_TAKE,project(shot),id(shot)).size()+1;
            ObjectNode input=obj().put("keyframeId",keyframeId).put("shotId",id(shot)).put("sceneId",required(shot,"sceneId")).put("episodeId",required(store.get(SCENE,required(shot,"sceneId")),"episodeId")).put("promptVersionId",id(prompt))
                .put("prompt",compiled.path("prompt").asText()).put("route",requestPlan.path("route").asText()).put("videoRequestRoute",requestPlan.path("videoRequestRoute").asText()).put("takeNo",takeNo);
            if(requestPlan.hasNonNull("firstFrameProviderUrl"))input.put("firstFrameProviderUrl",requestPlan.path("firstFrameProviderUrl").asText());
            copyPromptAudit(input,compiled,context);String strategy=compiled.path("strategy").asText("INDEPENDENT_CUT");int continuationDepth="CONTINUATION".equals(strategy)?previous.path("continuationDepth").asInt(0)+1:0;input.put("sequenceStrategy",strategy).put("sequenceRelation",text(context.path("shot"),"sequenceRelation")).put("continuationDepth",continuationDepth);if(previous.hasNonNull("id"))input.put("parentTakeId",text(previous,"id"));if(context.path("reanchorPlan").isObject())input.put("reanchorReason",text(context.path("reanchorPlan"),"reason"));if(context.path("retake").isObject())input.set("retake",context.path("retake").deepCopy());
            input.set("context",context);input.set("keyframeSnapshot",keyframeSnapshot);
            input.set("references",requestPlan.path("references").deepCopy());input.set("providerOptions",requestPlan.path("providerParameters").deepCopy());input.set("providerParameters",requestPlan.path("providerParameters").deepCopy());
            for(String field:List.of("modelId","modelProfileVersion","capabilityFingerprint","taskType","lockMode","route","activatedMaterials","excludedMaterials","referenceMapping","referenceAuthority","referenceBudget","rulePackFingerprint","rulePackUpstreamCommit","runtimeRuleIds","audioGenerationPolicy","modelProfile","preflight","resolution","ratio","desiredDuration","providerDuration","durationAdaptationReason","nativeAudio","watermark"))if(requestPlan.has(field))input.set(field,requestPlan.path(field).deepCopy());
            input.put("generationProfile",context.path("generationProfile").asText("TEST"));
            input.set("assetReferences",requestPlan.path("activatedMaterials").deepCopy());input.set("assetViewIds",shot.path("assetViewIds").deepCopy());
            input.set("clientRequestSnapshot",body.deepCopy());
            ObjectNode job=jobs.enqueue(project(shot),id(shot),"VIDEO",input,key(body));
            setShot(shot,"VIDEO_GENERATING");return job;
        });
    }
    private ObjectNode compactKeyframeSnapshot(ObjectNode source){
        ObjectNode snapshot=obj();
        for(String field:List.of("id","projectId","shotId","version","attemptNo","provider","sourceModel","providerUrl","providerUrlExpiresAt","archiveUrl","generationJobId","providerRequestId","promptVersionId","handoffStatus","qcStatus","qcScore","locked","selected","simulated","generationProfile","imageSize","assetViewIds","assetReferences","regeneratedFromId","regenerationMode","directorPlanVersion","dramaticBeatVersion","shotPlanVersion","semanticRole","timeSec","stateVersion","providerFrameMode"))
            if(source.has(field))snapshot.set(field,source.get(field).deepCopy());
        return snapshot;
    }
    public void checkHandoff(ObjectNode frame){
        checkReferenceSnapshot(frame);
        if(!"VOLCENGINE".equals(text(frame,"provider")))throw new WorkflowException("WRONG_PROVIDER","关键帧必须来自火山 Seedream");
        if(text(frame,"providerUrl").isBlank())throw new WorkflowException("MISSING_PROVIDER_URL","关键帧没有原始生产链接，归档链接不能替代");
        if(expired(frame)||"EXPIRED".equals(text(frame,"handoffStatus")))throw new WorkflowException("PROVIDER_URL_EXPIRED","原始链接已过期，请按原提示词重新生成关键帧并重新质检");
        if(!"PASSED".equals(text(frame,"qcStatus")))throw new WorkflowException("QC_REQUIRED","关键帧必须先通过质检");
        if(!frame.path("locked").asBoolean())throw new WorkflowException("LOCK_REQUIRED","请先锁定关键帧");
    }
    public ObjectNode review(ResourceKind kind,String id,ObjectNode body){
        if(!Set.of(KEYFRAME,VIDEO_TAKE,STORYBOARD).contains(kind))throw new IllegalArgumentException("此对象不支持画面质检");
        if(!body.path("passed").isBoolean())throw new IllegalArgumentException("请明确质检是否通过");
        double score=body.path("score").asDouble(body.path("passed").asBoolean()?100:0); if(score<0||score>100)throw new IllegalArgumentException("质检分数应为 0～100");
        return store.transaction(()->{
            ObjectNode item=store.getForUpdate(kind,id);
            boolean revokeLocked=item.path("locked").asBoolean()&&!body.path("passed").asBoolean();
            if(item.path("locked").asBoolean()&&!revokeLocked)throw new WorkflowException("LOCKED","已采用的版本不能重复通过质检；如发现视觉错误，请明确退回重做");
            if(kind==VIDEO_TAKE&&!"SUCCEEDED".equals(text(item,"providerStatus")))throw new WorkflowException("VIDEO_NOT_READY","视频尚未生成成功");
            String reviewer=body.path("reviewer").asText("HUMAN");if(!Set.of("HUMAN","AUTOMATIC").contains(reviewer))throw new IllegalArgumentException("reviewer 只能为 HUMAN 或 AUTOMATIC");
            ObjectNode qc=obj().put("projectId",project(item)).put("targetKind",kind.path()).put("targetId",id).put("shotId",required(item,"shotId"))
                .put("reviewSequence",nextReviewSequence(project(item),id)).put("passed",body.path("passed").asBoolean()).put("score",score).put("reviewer",reviewer).put("notes",body.path("notes").asText("人工审核")).put("generationProfile",item.path("generationProfile").asText(store.get(PROJECT,project(item)).path("generationProfile").asText("TEST")));
            String decision=body.path("decision").asText(body.path("passed").asBoolean()?"PASS":"REGENERATE");if(!Set.of("PASS","REGENERATE","MANUAL_FIX").contains(decision))throw new IllegalArgumentException("decision 只能为 PASS、REGENERATE 或 MANUAL_FIX");qc.put("decision",decision);
            for(String metric:List.of("characterConsistency","clothingConsistency","locationConsistency","propConsistency","composition","actionAccuracy","styleConsistency","motionContinuity","voiceConsistency","storyAccuracy","visualQuality"))if(body.has(metric)){double value=body.path(metric).asDouble(-1);if(value<0||value>100)throw new IllegalArgumentException(metric+" 必须在 0 到 100 之间");qc.put(metric,value);}
            if(!body.path("passed").asBoolean())qc.set("diagnosis",diagnoses.diagnose(body));
            if(body.has("expectedContext"))qc.set("expectedContext",body.path("expectedContext").deepCopy());if(body.has("qualityReviewResult"))qc.set("qualityReviewResult",body.path("qualityReviewResult").deepCopy());
            String reviewRequestId=text(body.path("qualityReviewResult").path("_provider"),"requestId");if(!reviewRequestId.isBlank())qc.put("providerRequestId",reviewRequestId);
            String reviewModel=text(body.path("qualityReviewResult").path("_provider"),"model");if(!reviewModel.isBlank())qc.put("reviewModel",reviewModel);
            if(!text(body,"assessmentKey").isBlank())qc.put("assessmentKey",text(body,"assessmentKey"));
            if(body.has("observedState")){
                qc.set("observedState",body.path("observedState").deepCopy());
                if(kind==VIDEO_TAKE){ObjectNode shot=store.get(SHOT,required(item,"shotId"));ArrayNode differences=stateDifferences(shot.path("endState"),body.path("observedState"),"");qc.set("observedDifferences",differences);
                    JsonNode observedStart=body.path("observedStartState").isObject()?body.path("observedStartState"):shot.path("startState");ArrayNode seamRisks=crossShotRisks(shot,observedStart);qc.set("crossShotRisks",seamRisks);if(body.path("passed").asBoolean()&&!seamRisks.isEmpty())throw new WorkflowException("CROSS_SHOT_QC_BLOCKED","当前视频起始状态与上一条已采用视频的实际末态不连续，请重生成或修正接镜策略");
                    if(differences.isEmpty())qc.put("deviationDecision","MATCH");else{String deviation=text(body,"deviationDecision");if(!Set.of("REGENERATE","ACCEPT_CANONICAL").contains(deviation))throw new WorkflowException("OBSERVED_DEVIATION_DECISION_REQUIRED","视频实际末态与计划不一致，请明确选择重生成或接受偏差并更新连续性状态");if("ACCEPT_CANONICAL".equals(deviation)&&!"HUMAN".equals(reviewer))throw new WorkflowException("HUMAN_DEVIATION_REVIEW_REQUIRED","只有人工审查可以接受实际偏差并更新连续性状态");if("REGENERATE".equals(deviation)&&body.path("passed").asBoolean())throw new WorkflowException("REGENERATE_CANNOT_PASS","选择重生成时不能把当前视频标记为通过");qc.put("deviationDecision",deviation);}}
            }
            store.create(QC_RESULT,QcGenerationProvenance.attach(qc,item));
            ObjectNode next=item.deepCopy().put("qcStatus",body.path("passed").asBoolean()?"PASSED":"FAILED").put("qcScore",score);
            if(revokeLocked)next.put("locked",false).put("selected",false).put("revokedAt",Instant.now().toString());
            if(body.has("observedState")){next.set("observedState",body.path("observedState").deepCopy());if(qc.has("observedDifferences"))next.set("observedDifferences",qc.path("observedDifferences").deepCopy());if(qc.has("deviationDecision"))next.set("deviationDecision",qc.path("deviationDecision"));}
            ObjectNode saved=store.update(kind,id,revision(item),next);
            ObjectNode shot=store.getForUpdate(SHOT,required(item,"shotId"));
            setShot(shot,body.path("passed").asBoolean()?(kind==VIDEO_TAKE?"VIDEO_QC":kind==KEYFRAME?"KEYFRAME_QC":"STORYBOARD_READY"):"NEEDS_REPAIR");return saved;
        });
    }
    private long nextReviewSequence(String projectId,String targetId){return store.list(QC_RESULT,projectId,null).stream().filter(review->targetId.equals(text(review,"targetId"))).mapToLong(review->review.path("reviewSequence").asLong(0)).max().orElse(0)+1;}
    private ArrayNode crossShotRisks(ObjectNode shot,JsonNode currentStart){ArrayNode result=JsonNodeFactory.instance.arrayNode();ObjectNode scene=store.get(SCENE,required(shot,"sceneId"));List<ObjectNode> ordered=new ArrayList<>(store.list(SHOT,project(shot),id(scene)).stream().filter(s->!s.path("stale").asBoolean()).toList());ordered.sort(Comparator.comparingInt(s->s.path("shotNo").asInt(Integer.MAX_VALUE)));int index=-1;for(int i=0;i<ordered.size();i++)if(id(shot).equals(id(ordered.get(i)))){index=i;break;}if(index<=0)return result;ObjectNode previousShot=ordered.get(index-1);for(ObjectNode take:store.list(VIDEO_TAKE,project(shot),id(previousShot)))if(take.path("locked").asBoolean()&&take.path("selected").asBoolean())for(var risk:continuityCompatibility.compareObserved(take,currentStart))result.add(obj().put("code",risk.code()).put("severity",risk.severity()).put("path",risk.path()).put("message",risk.message()));return result;}
    public ObjectNode lock(ResourceKind kind,String id,ObjectNode body){
        if(!Set.of(KEYFRAME,VIDEO_TAKE,STORYBOARD,AUDIO_CLIP,TIMELINE).contains(kind))throw new IllegalArgumentException("请在对应的故事或素材审查台确认此对象");
        ObjectNode locked=store.transaction(()->{
            ObjectNode item=store.getForUpdate(kind,id);
            if(Set.of(KEYFRAME,VIDEO_TAKE,STORYBOARD).contains(kind))checkReferenceSnapshot(item);
            if(item.path("locked").asBoolean()||item.path("identityLocked").asBoolean())return item;
            if(Set.of(KEYFRAME,VIDEO_TAKE,STORYBOARD).contains(kind)&&!"PASSED".equals(text(item,"qcStatus")))throw new WorkflowException("QC_REQUIRED","请先通过质检再采用");
            if(kind==KEYFRAME&&expired(item))throw new WorkflowException("PROVIDER_URL_EXPIRED","链接已过期，请重画关键帧");
            if(kind==AUDIO_CLIP&&!"SUCCEEDED".equals(text(item,"providerStatus")))throw new WorkflowException("AUDIO_NOT_READY","音频尚未生成成功");
            if(kind==TIMELINE&&!"READY".equals(text(item,"status")))throw new WorkflowException("TIMELINE_NOT_READY","时间线尚未准备完成");
            if(kind==TIMELINE){long content=item.path("contentRevision").asLong(1);if(text(item,"previewUrl").isBlank()||item.path("previewStale").asBoolean()||item.path("previewTimelineRevision").asLong(-1)!=content)throw new WorkflowException("PREVIEW_REQUIRED","请先生成当前剪辑版本的预览片");if(!"PASSED".equals(text(item,"timelineQaStatus"))||item.path("timelineQa").path("contentRevision").asLong(-1)!=content)throw new WorkflowException("TIMELINE_QA_REQUIRED","请先完成当前剪辑版本的时间线质检");}
            if(Set.of(KEYFRAME,VIDEO_TAKE,STORYBOARD).contains(kind)){
                ObjectNode shot=store.getForUpdate(SHOT,required(item,"shotId"));noActiveGeneration(id(shot));
                for(ObjectNode old:store.list(kind,project(item),id(shot)))if(!id(old).equals(id)&&old.path("selected").asBoolean())store.update(kind,id(old),revision(old),old.deepCopy().put("selected",false));
                String status=kind==VIDEO_TAKE?"VIDEO_LOCKED":kind==KEYFRAME?"KEYFRAME_LOCKED":"STORYBOARD_LOCKED";
                setShot(shot,status);
                if(kind==VIDEO_TAKE){
                    ObjectNode scene=store.getForUpdate(SCENE,required(shot,"sceneId"));
                    JsonNode observed=item.path("observedState");
                    if(!observed.isObject()||observed.isEmpty())throw new WorkflowException("END_STATE_REVIEW_REQUIRED","采用视频前，请记录人工复核的结束状态");
                    if(!item.path("observedDifferences").isEmpty()&&!"ACCEPT_CANONICAL".equals(text(item,"deviationDecision")))throw new WorkflowException("OBSERVED_DEVIATION_DECISION_REQUIRED","实际结束状态与计划不同，采用前必须明确接受偏差并更新连续性状态");
                    ObjectNode stateRequest=obj().put("locked",true).put("qcPassed",true);stateRequest.set("endState",observed);stateRequest.set("sceneState",scene.path("state"));
                    JsonNode nextState=production.applyLockedTake(stateRequest);
                    ObjectNode nextScene=scene.deepCopy();nextScene.set("state",nextState);nextScene.put("stateSourceTakeId",id);nextScene.put("stateSourceShotId",id(shot));
                    store.update(SCENE,id(scene),revision(scene),nextScene);
                }
            }
            if(kind==AUDIO_CLIP){
                for(ObjectNode old:store.list(AUDIO_CLIP,project(item),required(item,"shotId")))if(!id(old).equals(id)&&text(old,"dialogueLineId").equals(text(item,"dialogueLineId"))&&old.path("selected").asBoolean())store.update(AUDIO_CLIP,id(old),revision(old),old.deepCopy().put("selected",false));
            }
            ObjectNode next=item.deepCopy().put("locked",true).put("selected",true).put("lockedAt",Instant.now().toString());if(kind==CHARACTER)next.put("identityLocked",true);if(kind==KEYFRAME)next.put("selectedPurpose",MediaPurpose.FINAL_REFERENCE.name());
            return store.update(kind,id,revision(item),next);
        });
        if(kind==KEYFRAME&&body.path("generateVideo").asBoolean(true)){
            ObjectNode response=locked.deepCopy();response.set("videoJob",video(id,body));return response;
        }
        return locked;
    }
    private ArrayNode stateDifferences(JsonNode planned,JsonNode observed,String path){ArrayNode result=JsonNodeFactory.instance.arrayNode();if(planned.isObject()&&observed.isObject()){Set<String> fields=new TreeSet<>();planned.fieldNames().forEachRemaining(fields::add);observed.fieldNames().forEachRemaining(fields::add);for(String field:fields){String child=path.isBlank()?field:path+"."+field;result.addAll(stateDifferences(planned.path(field),observed.path(field),child));}}else if(!planned.equals(observed)){ObjectNode difference=obj().put("path",path);difference.set("planned",planned.deepCopy());difference.set("observed",observed.deepCopy());result.add(difference);}return result;}
    public ObjectNode reviseShot(String shotId){return store.transaction(()->{
        ObjectNode shot=store.getForUpdate(SHOT,shotId);noActiveGeneration(shotId);
        ObjectNode next=shot.deepCopy().put("status","NEEDS_REPAIR");
        ObjectNode previous=shot.deepCopy();previous.remove("editHistory");next.withArray("editHistory").add(previous);
        return store.update(SHOT,shotId,revision(shot),next);
    });}
    public ObjectNode qc(String kind,String id,ObjectNode body){
        ResourceKind k=ResourceKind.fromPath(kind); if(k!=KEYFRAME&&k!=VIDEO_TAKE)throw new IllegalArgumentException("质检对象无效");
        throw new WorkflowException("VISUAL_REVIEW_REQUIRED","请查看实际画面，核对人物、定妆、道具和空间后提交审查；当前文本模型不能执行视觉质检");
    }
    public ObjectNode context(ObjectNode source){
        if(source.path("stale").asBoolean())throw new WorkflowException("STALE_SHOT","此镜头依据的剧本已经修改，请使用当前确认版本重新拆镜");
        ObjectNode shot=source.deepCopy();shot.put("shotId",id(source));AssetDependencyAnalyzer.Level assetDependency=assetDependency(source);
        ObjectNode context=obj();context.set("shot",shot);context.set("assets",assets(project(source),assetDependency));
        ObjectNode scene=store.get(SCENE,required(source,"sceneId"));context.set("continuitySnapshot",development.approvedSnapshot(store.get(EPISODE,required(scene,"episodeId"))));context.set("previousState",scene.path("initialState").isObject()?scene.path("initialState"):obj());context.set("sceneContinuityPolicy",resolvedSceneContinuityPolicy(scene,store.get(PROJECT,project(source))));
        ObjectNode filteredAssets=contextResolver.filterAssets((ObjectNode)context.path("assets"),source);context.set("assets",filteredAssets);
        String style=store.get(PROJECT,project(source)).path("style").asText("写实电影");context.put("style",style);filteredAssets.put("style",style);
        if(source.path("storyTime").isNumber()){
            List<String> visible=new ArrayList<>();source.path("characterIds").forEach(v->visible.add(v.asText()));
            context.set("storyFacts",factResolver.resolve(project(source),source.path("storyTime").asDouble(),visible).path("facts").deepCopy());
            context.set("relationships",relationshipResolver.resolve(project(source),source.path("storyTime").asDouble(),visible).path("relationships").deepCopy());
            ObjectNode states=obj(); for(String characterId:visible) states.set(characterId,characterStateResolver.resolve(project(source),characterId,source.path("storyTime").asDouble())); context.set("characterStates",states);
            String locationId=text(source,"locationId"); if(!locationId.isBlank())context.set("locationState",locationStateResolver.resolve(project(source),locationId,source.path("storyTime").asDouble()));
            ObjectNode propStates=obj();source.path("propIds").forEach(value->propStates.set(value.asText(),propStateResolver.resolve(project(source),value.asText(),source.path("storyTime").asDouble())));context.set("propStates",propStates);
            context.put("storyTime",source.path("storyTime").asDouble());
        }
        List<ObjectNode> ordered=new ArrayList<>(store.list(SHOT,project(source),id(scene)).stream().filter(s->!s.path("stale").asBoolean()).toList());ordered.sort(Comparator.comparingInt(n->n.path("shotNo").asInt(Integer.MAX_VALUE)));
        // Reconstruct the confirmed scene ledger, rather than handing the
        // director only the immediately preceding shot.  Off-screen assets
        // remain available when they re-enter later in the scene.
        ObjectNode ledger=context.path("previousState").deepCopy();
        int targetIndex=-1;for(int i=0;i<ordered.size();i++)if(id(ordered.get(i)).equals(id(source))){targetIndex=i;break;}
        for(int index=0;index<ordered.size();index++){
            ObjectNode current=ordered.get(index);
            if(id(current).equals(id(source))){
                if(index>0)context.set("previousShot",ordered.get(index-1));
                context.set("previousState",ledger);
                break;
            }
            JsonNode delta=current.path("endState");
            for(ObjectNode candidate:store.list(VIDEO_TAKE,project(source),id(current)))if(candidate.path("locked").asBoolean()&&candidate.path("selected").asBoolean()){
                ObjectNode previous=candidate.deepCopy();previous.put("qcPassed","PASSED".equals(text(previous,"qcStatus")));previous.put("providerUrl",text(previous,"videoUrl"));
                if(index==targetIndex-1)context.set("previousTake",previous);
                if(previous.path("observedState").isObject())delta=previous.path("observedState");break;
            }
            mergeState(ledger,delta);
        }
        return context;
    }
    private static void mergeState(ObjectNode ledger,JsonNode delta){
        if(!delta.isObject())return;
        delta.fields().forEachRemaining(entry->{JsonNode value=entry.getValue(),existing=ledger.get(entry.getKey());
            if(value.isObject()&&existing!=null&&existing.isObject())mergeState((ObjectNode)existing,value);
            else ledger.set(entry.getKey(),value.deepCopy());
        });
    }
    private ObjectNode resolvedSceneContinuityPolicy(JsonNode scene,JsonNode project){JsonNode configured=scene.path("sceneContinuityPolicy").isObject()?scene.path("sceneContinuityPolicy"):project.path("sceneContinuityPolicy");ObjectNode policy=configured.isObject()?(ObjectNode)configured.deepCopy():obj();int max=policy.path("maxContinuationDepth").asInt(2);if(max<1||max>8)throw new WorkflowException("SCENE_CONTINUITY_POLICY_INVALID","maxContinuationDepth 必须在 1 到 8 之间");policy.put("maxContinuationDepth",max);policy.putIfAbsent("resetAtSceneBoundary",BooleanNode.TRUE);policy.putIfAbsent("reanchorFromCanonical",BooleanNode.TRUE);policy.putIfAbsent("reanchorOnIdentityDrift",BooleanNode.TRUE);policy.putIfAbsent("reanchorOnLocationDrift",BooleanNode.TRUE);policy.putIfAbsent("driftWarningCount",IntNode.valueOf(0));return policy;}
    private ObjectNode assets(String projectId){return assets(projectId,AssetDependencyAnalyzer.Level.A3);}
    private ObjectNode assets(String projectId,AssetDependencyAnalyzer.Level level){ObjectNode assets=obj();String coreId=required(store.get(PROJECT,projectId),"activeStoryDocumentId");for(ResourceKind kind:List.of(CHARACTER,CHARACTER_LOOK,LOCATION,PROP)){ArrayNode array=assets.putArray(kind==CHARACTER_LOOK?"looks":kind.path());for(ObjectNode a:store.list(kind,projectId,null))if(coreId.equals(text(a,"storyBibleId"))&&!a.path("stale").asBoolean()){ObjectNode value=a.deepCopy();if(kind!=CHARACTER){ArrayNode views=value.putArray("approvedViews");assetViews.approvedReferences(projectId,coreId,id(a),level).forEach(views::add);}array.add(value);}}return assets;}
    private AssetDependencyAnalyzer.Level assetDependency(JsonNode source){String value=text(source.path("directorRuleProfile"),"assetDependency");if(value.isBlank())value=text(source,"assetDependencyLevel");try{return value.isBlank()?AssetDependencyAnalyzer.Level.A3:AssetDependencyAnalyzer.Level.valueOf(value);}catch(IllegalArgumentException error){throw new WorkflowException("ASSET_DEPENDENCY_INVALID","素材依赖等级无效："+value);}}
    private void validateCharacters(JsonNode assets,JsonNode shot){
        List<String> requiredAssets=new ArrayList<>();requiredAssets.add(required(shot,"locationId"));shot.path("propIds").forEach(p->requiredAssets.add(p.asText()));
        for(JsonNode ref:shot.path("characterIds")){
            ObjectNode actor=store.get(CHARACTER,ref.asText());
            if(!project(actor).equals(project(shot)))throw new IllegalArgumentException("人物不属于当前项目");
            String lookId=required(shot.path("startState").path("characters").path(ref.asText()),"lookId");
            if(!ref.asText().equals(required(store.get(CHARACTER_LOOK,lookId),"characterId")))throw new WorkflowException("LOOK_OWNER_MISMATCH","定妆与人物不匹配");requiredAssets.add(lookId);
        }
        assetViews.requireReady(project(shot),required(store.get(PROJECT,project(shot)),"activeStoryDocumentId"),requiredAssets,assetDependency(shot));
    }
    public void checkReferenceSnapshot(JsonNode snapshot){
        if(snapshot.path("assetReferencesStale").asBoolean())throw new WorkflowException("ASSET_VERSION_CHANGED","此结果使用的素材版本已经修改，请用当前已批准素材重新生成");
        JsonNode ids=snapshot.path("assetViewIds");if(!ids.isArray()||ids.isEmpty()){if(assetDependency(snapshot)==AssetDependencyAnalyzer.Level.A0)return;throw new WorkflowException("ASSET_REFERENCES_REQUIRED","缺少已批准素材视图的版本快照，请使用新流程生成");}
        for(JsonNode id:ids){ObjectNode view=store.get(ASSET_VIEW,id.asText());String coreId=required(store.get(PROJECT,project(view)),"activeStoryDocumentId");
            if(view.path("stale").asBoolean()||!view.path("approved").asBoolean()||!coreId.equals(text(view,"coreId")))throw new WorkflowException("ASSET_VERSION_CHANGED","引用的素材视图已被修改或退回，请重新生成对应镜头");}
    }
    private ObjectNode savePrompt(ObjectNode shot,String purpose,JsonNode compiled,JsonNode context){
        int version=store.list(PROMPT_VERSION,project(shot),null).size()+1;
        ObjectNode prompt=obj().put("projectId",project(shot)).put("shotId",id(shot)).put("purpose",purpose).put("version",version)
            .put("prompt",compiled.path("prompt").asText()).put("compilerVersion",compiled.path("compilerVersion").asText("1.0"));
        if(compiled.path("promptIR").isObject())prompt.set("promptIR",compiled.path("promptIR").deepCopy());
        copyPromptAudit(prompt,compiled,context);prompt.set("inputSnapshot",context.deepCopy());return store.create(PROMPT_VERSION,prompt);
    }
    private ObjectNode outputProfile(String projectId){ObjectNode project=store.get(PROJECT,projectId);return obj().put("ratio",project.path("ratio").asText("9:16")).put("resolution",project.path("resolution").asText("1080p")).put("fps",project.path("fps").asInt(30));}
    private void copyPromptAudit(ObjectNode target,JsonNode compiled,JsonNode context){String references=hash(compiled.path("references").toString()),continuity=hash(context.path("continuitySnapshot").toString());ObjectNode sequence=obj();for(String field:List.of("previousState","previousTake","sceneContinuityPolicy"))if(context.has(field))sequence.set(field,context.path(field).deepCopy());for(String field:List.of("startState","endState","sequenceRelation","completedBeats","currentBeat","reservedFutureBeats"))if(context.path("shot").has(field))sequence.set(field,context.path("shot").path(field).deepCopy());target.put("normalizedPromptHash",hash(compiled.path("prompt").asText().replaceAll("\\s+"," ").trim())).put("referenceBindingsHash",references).put("referenceAuthorityFingerprint",references).put("continuitySnapshotHash",continuity).put("sequenceStateFingerprint",hash(sequence.toString())).put("providerCapabilitiesVersion",context.path("providerCapabilities").path("version").asText("UNKNOWN")).put("capabilityFingerprint",context.path("providerCapabilities").path("capabilityFingerprint").asText("UNKNOWN")).put("sequenceCompilerVersion",compiled.path("compilerVersion").asText("1.0")).put("generationProfile",context.path("generationProfile").asText("TEST")).put("modelId",context.path("providerCapabilities").path("modelId").asText("UNKNOWN"));}
    private String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void attachRetake(ObjectNode context,ObjectNode shot){List<ObjectNode> reviews=store.list(QC_RESULT,project(shot),null);for(int i=reviews.size()-1;i>=0;i--){ObjectNode review=reviews.get(i);if(id(shot).equals(text(review,"shotId"))&&"video-takes".equals(text(review,"targetKind"))&&!review.path("passed").asBoolean()){JsonNode diagnosis=review.path("diagnosis");String issue=diagnosis.path("failureCodes").path(0).asText("VISUAL_QC_FAILED"),repair=diagnosis.path("recommendedRepair").asText("REGENERATE");ObjectNode retake=obj().put("sourceReviewId",id(review)).put("issueCode",issue).put("repairStrategy",repair);retake.set("issueCodes",diagnosis.path("failureCodes").deepCopy());JsonNode plan=diagnosis.path("repairPlan");if(plan.isObject()){retake.set("repairPlan",plan.deepCopy());retake.set("changedPromptSections",plan.path("changedPromptSections").deepCopy());}else retake.putArray("changedPromptSections").add(retakeSection(issue));context.set("retake",retake);return;}}}
    static String retakeSection(String issue){
        return QualityDiagnosisService.promptSection(issue);
    }
    private void noActiveGeneration(String shotId){for(ObjectNode job:store.list(GENERATION_JOB,null,null))if(shotId.equals(text(job,"shotId"))&&!Set.of("ARCHIVE","VIDEO_QC").contains(text(job,"type"))){
        if(job.path("submissionUncertain").asBoolean())throw new WorkflowException("SUBMISSION_UNCERTAIN","此镜头已有服务商结果不确定的请求，请先核对请求记录，避免重复提交");
        if(Set.of("QUEUED","RUNNING","RETRY_WAIT","UNKNOWN","WAITING_HUMAN").contains(text(job,"status")))throw new WorkflowException("GENERATION_ACTIVE","此镜头已有任务运行或等待对账，请先处理该任务");
    }}
    public void setShot(ObjectNode shot,String status){ObjectNode next=shot.deepCopy();String from=text(shot,"status");next.put("status",status);if(!from.equals(status))next.withArray("statusHistory").add(obj().put("from",from).put("to",status).put("at",Instant.now().toString()));store.update(SHOT,id(shot),revision(shot),next);}
    private String key(JsonNode body){return body.path("requestKey").asText(UUID.randomUUID().toString());}
    private ObjectNode existingJob(String projectId,String type,String shotId,JsonNode body){
        if(text(body,"requestKey").isBlank())return null;
        for(ObjectNode job:store.list(GENERATION_JOB,projectId,null))if(text(body,"requestKey").equals(text(job,"requestKey"))){
            if(!type.equals(text(job,"type"))||!shotId.equals(text(job,"shotId"))||!job.path("inputSnapshot").path("clientRequestSnapshot").equals(body))throw new WorkflowException("IDEMPOTENCY_CONFLICT","请求标识已用于不同操作或输入");return job;
        }return null;
    }
}
