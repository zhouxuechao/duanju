package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.job.DirectorGenerationService;
import com.yourapp.drama.production.AssetDependencyAnalyzer;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class PipelineRunService {
    /** Public production stages. Asset preparation and shot keyframes have independent restart checkpoints. */
    private static final List<String> STAGES=List.of("PREFLIGHT","STORY","DIRECTOR","ASSET","KEYFRAME","VIDEO","AUDIO","TIMELINE","PREVIEW","CREATIVE_QA","FINAL");
    private final DocumentStore store;
    private final PipelinePreflightService preflight;
    private final ObjectMapper mapper;
    private final StoryDevelopmentService development;
    private final AssetViewService assetViews;
    private final WorkflowService workflow;
    private final PostProductionService post;
    private final StudioService studio;
    private final GenerationWorker worker;
    private final DirectorGenerationService director;
    private final AutomaticVisualReviewService visualReview;
    public PipelineRunService(DocumentStore store,PipelinePreflightService preflight,ObjectMapper mapper,StoryDevelopmentService development,
                              AssetViewService assetViews,WorkflowService workflow,PostProductionService post,StudioService studio,GenerationWorker worker,
                              DirectorGenerationService director,AutomaticVisualReviewService visualReview){
        this.store=store;this.preflight=preflight;this.mapper=mapper;this.development=development;this.assetViews=assetViews;this.workflow=workflow;this.post=post;this.studio=studio;this.worker=worker;this.director=director;this.visualReview=visualReview;
    }

    public ObjectNode start(String projectId,ObjectNode request){
        store.get(PROJECT,projectId);
        String mode=text(request,"mode").isBlank()?"MOCK":text(request,"mode").toUpperCase(Locale.ROOT);
        String scenario=text(request,"scenarioId").isBlank()?"golden-basic":text(request,"scenarioId");
        ObjectNode run=store.create(PIPELINE_RUN,obj().put("projectId",projectId).put("scenarioId",scenario).put("mode",mode)
            .put("status","RUNNING").put("attempt",1).put("startedAt",Instant.now().toString()).put("plannedCost",0).put("actualCost",0).put("wasteCost",0));
        if(Set.of("MOCK","MEDIA","CANARY").contains(mode))try{drive(run);}catch(RuntimeException error){run=recordFailure(id(run),error);}
        return audit(run,false);
    }

    public ObjectNode resume(String runId){
        ObjectNode run=store.transaction(()->{ObjectNode current=store.getForUpdate(PIPELINE_RUN,runId);if("SUCCESS".equals(text(current,"status")))return current;return store.update(PIPELINE_RUN,runId,revision(current),current.deepCopy().put("status","RUNNING").put("attempt",current.path("attempt").asInt(1)+1).put("resumedAt",Instant.now().toString()));});
        if(!"SUCCESS".equals(text(run,"status"))&&Set.of("MOCK","MEDIA","CANARY").contains(text(run,"mode")))try{drive(run);}catch(RuntimeException error){run=recordFailure(id(run),error);}
        return audit(run,true);
    }

    public ObjectNode get(String runId){return response(store.get(PIPELINE_RUN,runId));}

    /**
     * The FREE runner deliberately composes the same durable services used by the UI.
     * It never calls a provider adapter directly and it only auto-accepts simulated
     * review results in MOCK/MEDIA modes.
     */
    private void drive(ObjectNode run){
        String projectId=project(run),mode=text(run,"mode");
        boolean pipelineCanary=pipelineCanary(projectId);
        if(pipelineCanary)requirePipelineProfile(store.get(PROJECT,projectId),run);
        ObjectNode readiness=preflight.review(projectId,mode);
        if(!readiness.path("ready").asBoolean())throw new WorkflowException("PREFLIGHT_BLOCKED","Pipeline 自检未通过："+readiness.path("blocking"));
        driveStory(projectId);
        driveAssetViews(projectId);
        driveShotPlans(projectId);
        driveKeyframes(projectId,"golden-basic".equals(text(run,"scenarioId")),"MOCK".equals(mode),"phase-b-production".equals(text(run,"scenarioId")),pipelineCanary);
        driveVideos(projectId,pipelineCanary);
        driveAudio(projectId,pipelineCanary);
        driveTimelines(projectId);
    }

    private void driveStory(String projectId){
        int expected=Math.max(1,store.get(PROJECT,projectId).path("episodeCount").asInt(1));
        for(int step=0;step<512;step++){
            List<ObjectNode> current=store.list(STORY_DOCUMENT,projectId,null).stream().filter(d->!d.path("stale").asBoolean()).toList();
            if(current.stream().noneMatch(d->"CORE".equals(text(d,"documentType")))){development.start(projectId,obj());continue;}
            Optional<ObjectNode> blocked=current.stream().filter(d->"PREMISE_REVIEW_REQUIRED".equals(text(d,"reviewStatus"))).findFirst();
            if(blocked.isPresent())throw new WorkflowException("PREMISE_REVIEW_REQUIRED","创意容量检查未通过，不能自动进入 CORE");
            Optional<ObjectNode> review=current.stream().filter(d->"REVIEW".equals(text(d,"reviewStatus")))
                .sorted(Comparator.comparingInt(this::storyOrder).thenComparingInt(d->d.path("batchNo").asInt(d.path("episodeNo").asInt()))).findFirst();
            if(review.isPresent()){ObjectNode doc=review.get(),request=obj().put("revision",revision(doc));if("CORE".equals(text(doc,"documentType")))request.put("batchSize",5);development.confirm(id(doc),request);continue;}
            long confirmed=current.stream().filter(d->"EPISODE_SCRIPT".equals(text(d,"documentType"))&&"CONFIRMED".equals(text(d,"reviewStatus"))).count();
            if(confirmed==expected)return;
            if(!worker.tickProject(projectId))throw stalled(projectId,"SCRIPT","故事阶段没有可执行任务，也没有待确认文档");
            failIfTerminal(projectId);
        }
        throw new WorkflowException("PIPELINE_STEP_LIMIT","故事阶段超过安全步数");
    }

    private int storyOrder(ObjectNode document){return switch(text(document,"documentType")){case "STORY_BRIEF"->0;case "CORE"->1;case "OUTLINE_BATCH"->2;case "EPISODE_SCRIPT"->3;default->4;};}

    private void driveAssetViews(String projectId){
        AssetDependencyAnalyzer.Level dependency=assetViews.requiredLevel(projectId);if(dependency==AssetDependencyAnalyzer.Level.A0)return;
        if(store.list(ASSET_VIEW,projectId,null).stream().noneMatch(v->!v.path("stale").asBoolean()))assetViews.generate(projectId,obj().put("assetDependencyLevel",dependency.name()));
        for(int step=0;step<512;step++){
            List<ObjectNode> views=store.list(ASSET_VIEW,projectId,null).stream().filter(v->!v.path("stale").asBoolean()&&(dependency!=AssetDependencyAnalyzer.Level.A1||v.path("master").asBoolean())).toList();
            if(!views.isEmpty()&&views.stream().allMatch(v->v.path("approved").asBoolean()))return;
            Optional<ObjectNode> review=views.stream().filter(v->"REVIEW".equals(text(v,"status"))).sorted(Comparator.comparing((ObjectNode v)->!v.path("master").asBoolean())).findFirst();
            if(review.isPresent()){ObjectNode view=review.get();assetViews.approve(id(view),obj().put("revision",revision(view)).put("reviewNote","FREE Pipeline 可重复素材合同验收"));continue;}
            if(!worker.tickProject(projectId))throw stalled(projectId,"ASSET_VIEW","素材多视图没有可执行任务，也没有待审查图片");
            failIfTerminal(projectId);
        }
        throw new WorkflowException("PIPELINE_STEP_LIMIT","素材多视图阶段超过安全步数");
    }

    private void driveShotPlans(String projectId){
        List<ObjectNode> scenes=ordered(SCENE,projectId,null,"sceneNo");
        for(ObjectNode scene:scenes)if(store.list(SHOT,projectId,id(scene)).stream().noneMatch(s->!s.path("stale").asBoolean()))workflow.plan(id(scene),obj().put("requestKey","pipeline-plan-"+id(scene)));
        for(int pass=0;pass<8;pass++){drain(projectId,1024);if(!director.reconcileProject(projectId))break;}
        for(ObjectNode scene:scenes)if(store.list(SHOT,projectId,id(scene)).stream().noneMatch(s->!s.path("stale").asBoolean()))throw new WorkflowException("SHOT_PLAN_MISSING","导演任务结束后仍没有镜头");
    }

    private void driveKeyframes(String projectId,boolean exerciseRetake,boolean deterministicAcceptance,boolean canaryAcceptance,boolean pipelineCanary){
        List<ObjectNode> shots=orderedShots(projectId);boolean first=true,skipPrevis=skipPrevis(projectId);
        for(ObjectNode shot:shots){
            if(first&&!skipPrevis&&!approved(projectId,id(shot),STORYBOARD).isPresent()){
                ObjectNode job=workflow.image(id(shot),"STORYBOARD",obj().put("requestKey","pipeline-storyboard-"+id(shot)));complete(job);
                ObjectNode board=latest(projectId,id(shot),STORYBOARD);workflow.review(STORYBOARD,id(board),visualPass());workflow.lock(STORYBOARD,id(board),obj().put("generateVideo",false));
            }
            if(approved(projectId,id(shot),KEYFRAME).isEmpty()){
                List<ObjectNode> frames=store.list(KEYFRAME,projectId,id(shot));ObjectNode job;
                if(pipelineCanary&&!frames.isEmpty()){
                    ObjectNode frame=frames.getLast();
                    if("FAILED".equals(text(frame,"qcStatus")))throw new WorkflowException("KEYFRAME_QC_REVIEW_REQUIRED","Pipeline Canary 关键帧质检失败，禁止自动生成第二张关键帧");
                    if(!"PASSED".equals(text(frame,"qcStatus")))visualReview.review(id(frame),obj().put("apply",true).put("allowAutomaticRepair",false).put("deterministicAcceptance",deterministicAcceptance).put("canaryAcceptance",canaryAcceptance).put("requestKey","pipeline-keyframe-qc-"+id(frame)));
                    frame=store.get(KEYFRAME,id(frame));
                    if(!"PASSED".equals(text(frame,"qcStatus")))throw new WorkflowException("KEYFRAME_QC_REVIEW_REQUIRED","关键帧自动质检未通过，Pipeline Canary 禁止自动返修");
                    workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));first=false;continue;
                }
                if(!frames.isEmpty()&&"FAILED".equals(text(frames.getLast(),"qcStatus")))job=workflow.regenerateLatestKeyframe(id(frames.getLast()),obj().put("requestKey","pipeline-keyframe-retry-"+id(shot)));
                else job=workflow.image(id(shot),"KEYFRAME",obj().put("requestKey","pipeline-keyframe-"+id(shot)+"-"+frames.size()));
                complete(job);ObjectNode frame=latest(projectId,id(shot),KEYFRAME);
                boolean alreadyExercised=frames.stream().anyMatch(f->"FAILED".equals(text(f,"qcStatus")));
                if(first&&exerciseRetake&&!alreadyExercised){workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("score",35).put("reviewer","HUMAN").put("decision","REGENERATE").put("notes","Golden Flow 故障注入：构图不满足验收"));job=workflow.regenerateLatestKeyframe(id(frame),obj().put("requestKey","pipeline-keyframe-retake-"+id(shot)));complete(job);frame=latest(projectId,id(shot),KEYFRAME);}
                visualReview.review(id(frame),obj().put("apply",true).put("allowAutomaticRepair",false).put("deterministicAcceptance",deterministicAcceptance).put("canaryAcceptance",canaryAcceptance).put("requestKey","pipeline-keyframe-qc-"+id(frame)));frame=store.get(KEYFRAME,id(frame));
                if(!"PASSED".equals(text(frame,"qcStatus")))throw new WorkflowException("KEYFRAME_QC_PENDING","关键帧自动质检未通过，Pipeline 不会自动返修");
                workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
            }
            first=false;
        }
    }

    private void driveVideos(String projectId,boolean pipelineCanary){
        for(ObjectNode shot:orderedShots(projectId))if(approved(projectId,id(shot),VIDEO_TAKE).isEmpty()){
            ObjectNode frame=approved(projectId,id(shot),KEYFRAME).orElseThrow();
            List<ObjectNode> takes=store.list(VIDEO_TAKE,projectId,id(shot));
            if(pipelineCanary&&(!takes.isEmpty()||hasJob(projectId,id(shot),"VIDEO"))){drain(projectId,64);takes=store.list(VIDEO_TAKE,projectId,id(shot));}
            ObjectNode take=takes.stream().filter(t->"SUCCEEDED".equals(text(t,"providerStatus"))&&Set.of("PENDING","PASSED").contains(text(t,"qcStatus"))).findFirst().orElse(null);
            if(pipelineCanary&&take==null&&!takes.isEmpty()){
                boolean qcFailed=takes.stream().anyMatch(t->"FAILED".equals(text(t,"qcStatus"))||"MANUAL_FIX".equals(text(t,"decision")));
                throw new WorkflowException(qcFailed?"VIDEO_QC_REVIEW_REQUIRED":"PIPELINE_CANARY_SECOND_TAKE_BLOCKED",qcFailed?"Pipeline Canary 视频质检失败，禁止自动生成第二条视频":"Pipeline Canary 已存在视频任务或成片，禁止自动提交第二条视频");
            }
            if(pipelineCanary&&take==null&&hasJob(projectId,id(shot),"VIDEO"))throw new WorkflowException("PIPELINE_CANARY_SECOND_TAKE_BLOCKED","Pipeline Canary 已有视频生成任务，禁止自动重新提交");
            if(take==null){ObjectNode job=workflow.video(id(frame),obj().put("requestKey","pipeline-video-"+id(shot)+"-"+takes.size()).put("allowAutomaticRepair",false));complete(job);take=latest(projectId,id(shot),VIDEO_TAKE);}
            drain(projectId,64);take=store.get(VIDEO_TAKE,id(take));
            if(!"PASSED".equals(text(take,"qcStatus")))throw new WorkflowException(pipelineCanary?"VIDEO_QC_REVIEW_REQUIRED":"VIDEO_QC_PENDING","视频自动质检尚未通过");
            workflow.lock(VIDEO_TAKE,id(take),obj());
        }
        drain(projectId,512);
    }

    private void driveAudio(String projectId,boolean pipelineCanary){
        ObjectNode projectDocument=store.get(PROJECT,projectId);String canaryVoice=pipelineCanary?PipelineLiveExecutionProfile.phaseB().requireVoiceId(text(projectDocument,"canaryTtsVoiceId")):"";
        Map<String,String> voices=new HashMap<>();
        for(ObjectNode profile:store.list(VOICE_PROFILE,projectId,null))if(profile.hasNonNull("characterId")&&profile.path("approved").asBoolean()&&(!pipelineCanary||canaryVoice.equals(text(profile,"providerVoiceId"))))voices.put(text(profile,"characterId"),id(profile));
        for(ObjectNode line:store.list(DIALOGUE_LINE,projectId,null)){
            String characterId=required(line,"characterId"),voiceId=voices.get(characterId);
            if(voiceId==null){ObjectNode character=store.get(CHARACTER,characterId);String providerVoiceId=pipelineCanary?canaryVoice:"mock-voice-"+characterId;ObjectNode profile=studio.create(VOICE_PROFILE,obj().put("projectId",projectId).put("characterId",characterId).put("name",text(character,"name")+(pipelineCanary?" · Canary 普通话音色":" · FREE 音色")).put("providerVoiceId",providerVoiceId).put("approved",true));voiceId=id(profile);voices.put(characterId,voiceId);}
            if(!voiceId.equals(text(line,"voiceProfileId"))){ObjectNode change=obj().put("revision",revision(line)).put("voiceProfileId",voiceId);line=studio.update(DIALOGUE_LINE,id(line),change);}
            if(text(line,"spokenText").isBlank())line=post.dialect(id(line),obj().put("dialect",line.path("dialect").asText("MANDARIN")));
            String lineId=id(line),shotId=required(line,"shotId");
            boolean ready=store.list(AUDIO_CLIP,projectId,shotId).stream().anyMatch(a->lineId.equals(text(a,"dialogueLineId"))&&a.path("locked").asBoolean());
            if(!ready){ObjectNode job=pipelineCanary?jobForInput(projectId,"TTS","dialogueLineId",lineId).orElseGet(()->post.tts(lineId,obj().put("requestKey","pipeline-tts-"+lineId))):post.tts(lineId,obj().put("requestKey","pipeline-tts-"+lineId));complete(job);ObjectNode clip=store.list(AUDIO_CLIP,projectId,shotId).stream().filter(a->lineId.equals(text(a,"dialogueLineId"))).reduce((a,b)->b).orElseThrow();workflow.lock(AUDIO_CLIP,id(clip),obj());}
        }
        if(store.list(DIALOGUE_LINE,projectId,null).isEmpty())throw new WorkflowException("DIALOGUE_REQUIRED","FREE Golden Flow 必须包含对白与字幕验收");
    }

    private void driveTimelines(String projectId){
        for(ObjectNode episode:ordered(EPISODE,projectId,null,"episodeNo")){
            ObjectNode timeline=store.list(TIMELINE,projectId,id(episode)).stream().filter(t->!t.path("stale").asBoolean()).reduce((a,b)->b).orElse(null);
            if(timeline==null){ObjectNode request=obj().put("requestKey","pipeline-timeline-"+id(episode));request.set("soundDesign",post.soundDesign(id(episode),obj()));List<ObjectNode> clips=store.list(AUDIO_CLIP,projectId,null).stream().filter(c->c.path("locked").asBoolean()&&!text(c,"archiveUrl").isBlank()).toList();if(!clips.isEmpty()){long duration=authoredEpisodeDurationMs(projectId,id(episode));String source=required(clips.getFirst(),"archiveUrl");request.putArray("soundItems").add(obj().put("track","BGM").put("sourceUrl",source).put("startMs",0).put("durationMs",duration).put("volume",.12)).add(obj().put("track","SFX").put("sourceUrl",source).put("startMs",Math.max(0,duration/2-250)).put("durationMs",Math.min(500,duration)).put("volume",.45));}ObjectNode job=post.timeline(id(episode),request);complete(job);timeline=latest(projectId,id(episode),TIMELINE);}
            if(text(timeline,"previewUrl").isBlank()||timeline.path("previewStale").asBoolean()){complete(post.render(id(timeline),obj().put("quality","PREVIEW").put("requestKey","pipeline-preview-"+id(timeline))));timeline=store.get(TIMELINE,id(timeline));}
            ObjectNode qa=post.quality(id(timeline));if(!qa.path("passed").asBoolean())throw new WorkflowException("QA_BLOCKING","时间线质检未通过："+qa.path("failureCodes"));
            timeline=workflow.lock(TIMELINE,id(timeline),obj());
            if(text(timeline,"finalUrl").isBlank()){complete(post.render(id(timeline),obj().put("quality","FINAL").put("requestKey","pipeline-final-"+id(timeline))));timeline=store.get(TIMELINE,id(timeline));}
            if(!"PASSED".equals(text(timeline,"finalQaStatus"))){ObjectNode review=obj().put("decision","PASS").put("reviewer","FREE_PIPELINE").put("notes","模拟媒体合同、连续性和叙事节点验收通过");for(String metric:FinalCreativeQualityService.METRICS)review.put(metric,5);post.finalReview(id(timeline),review);}
        }
    }

    private long authoredEpisodeDurationMs(String projectId,String episodeId){long duration=0;for(ObjectNode scene:store.list(SCENE,projectId,episodeId))for(ObjectNode shot:store.list(SHOT,projectId,id(scene)))if(!shot.path("stale").asBoolean())duration+=Math.round(shot.path("editDuration").asDouble(shot.path("duration").asDouble())*1000);if(duration<=0)throw new WorkflowException("EPISODE_DURATION_REQUIRED","生成声音时间线前必须已有可用镜头时长");return duration;}

    private ObjectNode visualPass(){ObjectNode review=obj().put("passed",true).put("score",100).put("reviewer","HUMAN").put("decision","PASS").put("notes","FREE Pipeline 确定性媒体合同验收");for(String metric:List.of("characterConsistency","clothingConsistency","locationConsistency","propConsistency","composition","actionAccuracy","styleConsistency","motionContinuity","voiceConsistency","storyAccuracy","visualQuality"))review.put(metric,100);return review;}
    private Optional<ObjectNode> approved(String projectId,String parentId,ResourceKind kind){return store.list(kind,projectId,parentId).stream().filter(v->v.path("selected").asBoolean()&&v.path("locked").asBoolean()&&"PASSED".equals(text(v,"qcStatus"))).findFirst();}
    private ObjectNode latest(String projectId,String parentId,ResourceKind kind){List<ObjectNode> values=store.list(kind,projectId,parentId);if(values.isEmpty())throw new WorkflowException("PIPELINE_ARTIFACT_MISSING",kind+" 未生成");return values.getLast();}
    private List<ObjectNode> ordered(ResourceKind kind,String projectId,String parentId,String field){List<ObjectNode> values=new ArrayList<>(store.list(kind,projectId,parentId));values.sort(Comparator.comparingInt(v->v.path(field).asInt(Integer.MAX_VALUE)));return values;}
    private List<ObjectNode> orderedShots(String projectId){List<ObjectNode> result=new ArrayList<>();for(ObjectNode episode:ordered(EPISODE,projectId,null,"episodeNo"))for(ObjectNode scene:ordered(SCENE,projectId,id(episode),"sceneNo"))result.addAll(ordered(SHOT,projectId,id(scene),"shotNo").stream().filter(s->!s.path("stale").asBoolean()).toList());return result;}
    private boolean pipelineCanary(String projectId){ObjectNode project=store.get(PROJECT,projectId);return project.path("testRun").asBoolean(false)&&"PIPELINE".equalsIgnoreCase(text(project,"testPhase"));}
    private void requirePipelineProfile(ObjectNode project,ObjectNode run){String voice=text(project,"canaryTtsVoiceId");PipelineLiveExecutionProfile.phaseB().requireVoiceId(voice);if(!"phase-b-production".equals(text(run,"scenarioId"))||!"phase-b-production".equals(text(project,"scenarioId"))||!"A1".equals(text(project,"assetDependencyLevel")))throw new WorkflowException("PIPELINE_EXECUTION_PROFILE_MISMATCH","Phase B Pipeline Canary 必须使用 phase-b-production / A1 固定执行档位");}
    private boolean hasJob(String projectId,String shotId,String type){return store.list(GENERATION_JOB,projectId,null).stream().anyMatch(job->type.equals(text(job,"type"))&&shotId.equals(text(job,"shotId")));}
    private Optional<ObjectNode> jobForInput(String projectId,String type,String field,String value){return store.list(GENERATION_JOB,projectId,null).stream().filter(job->type.equals(text(job,"type"))&&value.equals(text(job.path("inputSnapshot"),field))).findFirst();}
    private ObjectNode complete(ObjectNode submitted){for(int step=0;step<1024;step++){ObjectNode job=store.get(GENERATION_JOB,id(submitted));if("SUCCESS".equals(text(job,"status")))return job;if(Set.of("FAILED","CANCELLED").contains(text(job,"status")))throw new WorkflowException(text(job,"failureCode"),text(job,"failureReason"));if(Set.of("UNKNOWN","WAITING_HUMAN").contains(text(job,"status")))throw new WorkflowException("PROVIDER_STATUS_UNKNOWN","任务 "+id(job)+" 的服务商状态未知，必须先对账");if(!worker.tickProject(project(job)))throw stalled(project(job),text(job,"type"),"任务未结束且没有可执行工作");}throw new WorkflowException("PIPELINE_STEP_LIMIT","任务超过安全执行步数："+id(submitted));}
    private void drain(String projectId,int limit){for(int step=0;step<limit;step++){List<ObjectNode> active=store.list(GENERATION_JOB,projectId,null).stream().filter(j->Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(j,"status"))).toList();if(active.isEmpty()){failIfTerminal(projectId);return;}if(!worker.tickProject(projectId))throw stalled(projectId,"JOB_DRAIN","存在任务但当前无法安全执行");}throw new WorkflowException("PIPELINE_STEP_LIMIT","项目任务超过安全执行步数");}
    private void failIfTerminal(String projectId){store.list(GENERATION_JOB,projectId,null).stream().filter(j->Set.of("FAILED","UNKNOWN","WAITING_HUMAN").contains(text(j,"status"))).findFirst().ifPresent(j->{String status=text(j,"status");throw new WorkflowException("FAILED".equals(status)?text(j,"failureCode"):"PROVIDER_STATUS_UNKNOWN",text(j,"failureReason"));});}
    private WorkflowException stalled(String projectId,String stage,String message){Optional<ObjectNode> uncertain=store.list(GENERATION_JOB,projectId,null).stream().filter(j->j.path("submissionUncertain").asBoolean()||j.path("reconciliationRequired").asBoolean()).findFirst();return uncertain.map(j->new WorkflowException("PROVIDER_UNCERTAIN","任务 "+id(j)+" 状态不确定，必须先对账")).orElseGet(()->new WorkflowException("PIPELINE_STALLED",stage+"："+message));}
    private ObjectNode recordFailure(String runId,RuntimeException error){return store.transaction(()->{ObjectNode current=store.getForUpdate(PIPELINE_RUN,runId),next=current.deepCopy();String code=error instanceof WorkflowException workflowError?workflowError.code():"PIPELINE_FAILED";next.put("status","WAITING").put("lastErrorCode",code).put("lastErrorMessage",Optional.ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName())).put("lastRootCauseCategory",category(code)).put("failedAt",Instant.now().toString());return store.update(PIPELINE_RUN,runId,revision(current),next);});}
    private String category(String code){String value=code==null?"":code.toUpperCase(Locale.ROOT);if(value.contains("UNCERTAIN")||value.contains("RECONCILIATION"))return "PROVIDER_UNCERTAIN";if(value.contains("TIMEOUT")||value.contains("NETWORK")||value.contains("RATE_LIMIT")||value.contains("429")||value.contains("PROVIDER_UNAVAILABLE"))return "PROVIDER_TRANSIENT";if(value.contains("STRUCTURED")||value.contains("OUTPUT_TRUNCATED"))return "STRUCTURED_OUTPUT";if(value.contains("CONTINUITY")||value.contains("STATE_MISMATCH"))return "CONTINUITY";if(value.contains("QC")||value.contains("REVIEW_REQUIRED"))return "QA_BLOCKING";if(value.contains("MEDIA")||value.contains("RENDER")||value.contains("FFMPEG")||value.contains("ARCHIVE"))return "MEDIA";if(value.contains("CONFIG")||value.contains("PREFLIGHT")||value.contains("AUTH"))return "CONFIG";if(value.contains("INVALID")||value.contains("CONTRACT")||value.contains("SCHEMA"))return "CONTRACT";return "DATA";}

    private ObjectNode audit(ObjectNode run,boolean resume){
        String projectId=project(run),runId=id(run),mode=text(run,"mode");
        ObjectNode preflightResult=preflight.review(projectId,mode);
        Map<String,StageEvaluation> evaluations=evaluate(projectId,preflightResult);
        boolean blocked=false,failureRecorded=false;
        for(String stage:STAGES){
            StageEvaluation evaluation=evaluations.get(stage);
            String status=blocked?"PENDING":evaluation.success?"SUCCESS":"PENDING";
            if(!evaluation.success)blocked=true;
            Optional<ObjectNode> previous=latestStage(projectId,runId,stage);
            if(resume&&"SUCCESS".equals(status)&&previous.filter(p->"SUCCESS".equals(text(p,"status"))&&evaluation.inputFingerprint.equals(text(p,"inputFingerprint"))).isPresent())continue;
            int attempt=previous.map(p->p.path("attempt").asInt()+1).orElse(1);
            ObjectNode checkpoint=obj().put("projectId",projectId).put("pipelineRunId",runId).put("stage",stage).put("status",status).put("attempt",attempt)
                .put("startedAt",Instant.now().toString()).put("finishedAt",Instant.now().toString()).put("durationMs",0)
                .put("inputFingerprint",evaluation.inputFingerprint).put("outputFingerprint",evaluation.outputFingerprint)
                .put("safeRetry",!"PROVIDER_UNCERTAIN".equals(evaluation.rootCauseCategory)).put("cost",0);
            checkpoint.putArray("artifacts").addAll(evaluation.artifacts);
            if(!"SUCCESS".equals(status)){boolean useRunFailure=!failureRecorded&&!text(run,"lastErrorCode").isBlank();checkpoint.put("errorCode",useRunFailure?text(run,"lastErrorCode"):evaluation.errorCode).put("errorMessage",useRunFailure?text(run,"lastErrorMessage"):evaluation.message).put("rootCauseCategory",useRunFailure?text(run,"lastRootCauseCategory"):evaluation.rootCauseCategory);if(useRunFailure){checkpoint.put("safeRetry",!"PROVIDER_UNCERTAIN".equals(text(run,"lastRootCauseCategory")));failureRecorded=true;}}
            store.create(STAGE_RUN,checkpoint);
        }
        String resumeFrom=STAGES.stream().filter(stage->latestStage(projectId,runId,stage).map(p->!"SUCCESS".equals(text(p,"status"))).orElse(true)).findFirst().orElse("");
        ObjectNode updated=store.transaction(()->{ObjectNode current=store.getForUpdate(PIPELINE_RUN,runId),next=current.deepCopy();boolean success=resumeFrom.isBlank();next.put("status",success?"SUCCESS":"WAITING").put("resumeFromStage",resumeFrom).put("lastCheckedAt",Instant.now().toString());if(success)next.put("finishedAt",Instant.now().toString());return store.update(PIPELINE_RUN,runId,revision(current),next);});
        return response(updated);
    }

    private Map<String,StageEvaluation> evaluate(String projectId,ObjectNode pf){
        List<ObjectNode> docs=store.list(STORY_DOCUMENT,projectId,null),episodes=store.list(EPISODE,projectId,null),scenes=store.list(SCENE,projectId,null),shots=store.list(SHOT,projectId,null);
        Map<String,StageEvaluation> values=new LinkedHashMap<>();
        values.put("PREFLIGHT",evaluation(pf.path("ready").asBoolean(),"PREFLIGHT_BLOCKED","配置或工具自检存在阻塞项","CONFIG",pf,List.of()));
        List<ObjectNode> currentDocs=docs.stream().filter(d->!d.path("stale").asBoolean()).toList();
        boolean story=!episodes.isEmpty()&&!scenes.isEmpty()&&List.of("STORY_BRIEF","CORE","OUTLINE_BATCH","EPISODE_SCRIPT").stream().allMatch(type->currentDocs.stream().anyMatch(d->type.equals(text(d,"documentType"))&&"CONFIRMED".equals(text(d,"reviewStatus"))));
        values.put("STORY",evaluation(story,"STORY_INCOMPLETE","创作需求、核心、集纲、单集剧本或场景尚未确认完成","DATA",mapper.valueToTree(currentDocs),artifacts(currentDocs)));
        values.put("DIRECTOR",resources(shots,d->!d.path("stale").asBoolean(),"DIRECTOR_INCOMPLETE","尚未形成有效导演镜头计划"));
        AssetDependencyAnalyzer.Level assetDependency=assetViews.requiredLevel(projectId);List<ObjectNode> assetArtifacts=store.list(ASSET_VIEW,projectId,null).stream().filter(v->assetDependency!=AssetDependencyAnalyzer.Level.A1||v.path("master").asBoolean()).toList();
        values.put("ASSET",assetDependency==AssetDependencyAnalyzer.Level.A0?evaluation(true,"","","DATA",mapper.createArrayNode(),List.of()):resources(assetArtifacts,v->!v.path("stale").asBoolean()&&v.path("approved").asBoolean(),"ASSET_NOT_APPROVED","项目所需人物、场景或道具参考视图尚未确认"));
        List<ObjectNode> storyboardArtifacts=store.list(STORYBOARD,projectId,null);
        StageEvaluation boards=skipPrevis(projectId)?evaluation(true,"","","DATA",mapper.createArrayNode(),List.of()):resources(storyboardArtifacts,b->b.path("selected").asBoolean()&&b.path("locked").asBoolean()&&"PASSED".equals(text(b,"qcStatus")),"PREVIS_NOT_LOCKED","项目缺少已质检并锁定的构图预演");
        StageEvaluation keyframes=resourcesForShots(projectId,shots,KEYFRAME,"KEYFRAME_NOT_LOCKED","镜头缺少已质检并锁定的关键帧");
        ArrayNode imageArtifacts=mapper.createArrayNode();imageArtifacts.addAll(boards.artifacts);imageArtifacts.addAll(keyframes.artifacts);
        values.put("KEYFRAME",evaluation(boards.success&&keyframes.success,"KEYFRAME_INCOMPLETE","故事板或关键帧尚未全部质检并锁定","DATA",imageArtifacts,mapper.convertValue(imageArtifacts,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){})));
        values.put("VIDEO",resourcesForShots(projectId,shots,VIDEO_TAKE,"VIDEO_NOT_LOCKED","镜头缺少已质检并锁定的视频"));
        List<ObjectNode> dialogue=store.list(DIALOGUE_LINE,projectId,null),audio=store.list(AUDIO_CLIP,projectId,null);
        boolean tts=!dialogue.isEmpty()&&dialogue.stream().allMatch(line->audio.stream().anyMatch(a->id(line).equals(text(a,"dialogueLineId"))&&a.path("locked").asBoolean()));
        values.put("AUDIO",evaluation(tts,"AUDIO_INCOMPLETE","对白尚未全部生成并锁定配音","DATA",mapper.valueToTree(dialogue),artifacts(audio)));
        List<ObjectNode> timelines=store.list(TIMELINE,projectId,null);
        values.put("TIMELINE",resources(timelines,t->true,"TIMELINE_MISSING","尚未生成时间线"));
        values.put("PREVIEW",resources(timelines,t->!text(t,"previewUrl").isBlank()&&!t.path("previewStale").asBoolean(),"PREVIEW_MISSING","当前剪辑版本尚未生成有效预览"));
        values.put("CREATIVE_QA",resources(timelines,t->"PASSED".equals(text(t,"finalQaStatus"))&&"PASS".equals(text(t.path("finalCreativeQa"),"decision")),"CREATIVE_QA_PENDING","终片创作验收尚未通过"));
        values.put("FINAL",resources(timelines,t->!text(t,"finalUrl").isBlank()&&t.path("finalTechnicalQa").path("passed").asBoolean(),"FINAL_NOT_READY","终片尚未完成或技术质检未通过"));
        return values;
    }

    private StageEvaluation resources(List<ObjectNode> resources,java.util.function.Predicate<ObjectNode> accepted,String code,String message){boolean success=!resources.isEmpty()&&resources.stream().allMatch(accepted);return evaluation(success,code,message,"DATA",mapper.valueToTree(resources),artifacts(resources));}
    private StageEvaluation resourcesForShots(String projectId,List<ObjectNode> shots,ResourceKind kind,String code,String message){List<ObjectNode> candidates=store.list(kind,projectId,null);boolean success=!shots.isEmpty()&&shots.stream().allMatch(shot->candidates.stream().anyMatch(c->id(shot).equals(text(c,"shotId"))&&c.path("selected").asBoolean()&&c.path("locked").asBoolean()&&"PASSED".equals(text(c,"qcStatus"))));return evaluation(success,code,message,"DATA",mapper.valueToTree(candidates),artifacts(candidates));}
    private StageEvaluation evaluation(boolean success,String code,String message,String category,JsonNode source,List<String> artifacts){return new StageEvaluation(success,code,message,category,hash(source),success?hash(mapper.valueToTree(artifacts)):"",mapper.valueToTree(artifacts));}
    private List<String> artifacts(List<ObjectNode> docs){return docs.stream().map(Documents::id).toList();}
    private boolean skipPrevis(String projectId){return "SKIP".equalsIgnoreCase(store.get(PROJECT,projectId).path("previsMode").asText("REQUIRED"));}
    private Optional<ObjectNode> latestStage(String projectId,String runId,String stage){return store.list(STAGE_RUN,projectId,runId).stream().filter(s->stage.equals(text(s,"stage"))).max(Comparator.comparingInt(s->s.path("attempt").asInt()));}
    private ObjectNode response(ObjectNode run){ObjectNode out=run.deepCopy();ArrayNode stages=out.putArray("stages");for(String stage:STAGES)latestStage(project(run),id(run),stage).ifPresent(stages::add);return out;}
    private String hash(JsonNode node){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(node)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record StageEvaluation(boolean success,String errorCode,String message,String rootCauseCategory,String inputFingerprint,String outputFingerprint,ArrayNode artifacts){}
}
