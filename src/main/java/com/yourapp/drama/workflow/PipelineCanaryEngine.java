package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ProviderException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static com.yourapp.drama.workflow.Documents.obj;

/** Sequential, crash-resumable four-shot canary coordinator. It never retries or creates a second paid take. */
public final class PipelineCanaryEngine {
    private static final String IMAGE_MODEL="doubao-seedream-5-0-260128";
    private static final String VIDEO_MODEL="doubao-seedance-2-0-fast-260128";
    private final PipelineCanaryFixture fixture;private final PipelineCanaryState state;private final PipelineCanaryArtifactVault vault;
    private final Operations operations;private final Checkpoint checkpoint;private final Path evidencePath;private final ObjectMapper mapper=new ObjectMapper();

    public PipelineCanaryEngine(PipelineCanaryFixture fixture,PipelineCanaryState state,PipelineCanaryArtifactVault vault,
                                Operations operations,Checkpoint checkpoint,Path evidencePath){
        this.fixture=fixture;this.state=state;this.vault=vault;this.operations=operations;this.checkpoint=checkpoint;this.evidencePath=evidencePath;
        if(!state.snapshot().path("runId").asText().equals(vault.runId()))throw new IllegalArgumentException("Pipeline Canary state and runtime vault do not belong to the same run");
    }

    public Result execute() throws Exception {
        if("RECONCILIATION_REQUIRED".equals(state.snapshot().path("status").asText()))throw blocked("Pipeline Canary requires reconciliation before resume");
        if(state.snapshot().path("projectId").asText().isBlank()){
            state.storyPlanning();StoryIds ids=operations.plan(fixture);state.storyReady(ids.projectId(),ids.episodeId(),ids.sceneId());checkpoint.after("STORY_READY");
        }
        for(PipelineCanaryFixture.ShotPlan shot:fixture.shots())process(shot);
        String current=state.snapshot().path("status").asText();
        if(!Set.of("MEDIA_READY","TIMELINE_BUILDING","TIMELINE_READY","RENDERING","RENDER_READY","FINAL_QA","SUCCEEDED").contains(current)){
            state.mediaReady();checkpoint.after("MEDIA_READY");
        }
        state.beginTimeline();TimelineResult timeline=operations.timeline(fixture,state.snapshot(),vault);state.timelineReady(timeline.artifactId());checkpoint.after("TIMELINE_READY");
        RenderResult render;
        try{state.beginRender();render=operations.render(timeline);state.renderReady(render.artifactId());checkpoint.after("RENDER_READY");}
        catch(Exception error){state.localFailure("RENDER_FAILED");throw error;}
        state.beginFinalQa();QaResult qa=operations.finalQa(render,timeline);
        if(!qa.passed()){state.localFailure("FINAL_QA_FAILED");throw new WorkflowException("PIPELINE_CANARY_FINAL_QA_FAILED","Pipeline Canary final QA failed: "+qa.failureCodes());}
        state.succeeded();ObjectNode finalState=state.snapshot();writeEvidence(finalState,timeline,render,qa);return new Result(finalState,timeline,render,qa,evidencePath);
    }

    private void process(PipelineCanaryFixture.ShotPlan shot) throws Exception {
        ObjectNode cursor=state.shot(shot.shotId());String keyframeStatus=cursor.path("keyframeStatus").asText();
        if("SUBMITTING".equals(keyframeStatus)){state.requireReconciliation(shot.shotId(),"KEYFRAME_SUBMISSION_UNCERTAIN");throw blocked("Keyframe submission requires reconciliation");}
        if("FAILED".equals(keyframeStatus)||"RECONCILIATION_REQUIRED".equals(keyframeStatus))throw blocked("Keyframe step is not resumable without reconciliation");
        if(!"SUCCEEDED".equals(keyframeStatus)){
            state.beginKeyframe(shot.shotId());KeyframeResult result;
            try{result=operations.keyframe(shot);}catch(Exception error){providerFailure(shot.shotId(),"keyframeStatus","KEYFRAME_SUBMISSION_FAILED",error);throw error;}
            requireProviderUrl(result.providerUrl());vault.put(key(shot,"keyframe"),result.providerUrl());
            state.keyframeSucceeded(shot.shotId(),result.requestId(),result.artifactId(),host(result.providerUrl()),fingerprint(result.providerUrl()));checkpoint.after(shot.shotId()+":KEYFRAME_SUCCEEDED");
        }
        cursor=state.shot(shot.shotId());String videoStatus=cursor.path("videoStatus").asText();
        if("SUBMITTING".equals(videoStatus)){state.requireReconciliation(shot.shotId(),"VIDEO_SUBMISSION_UNCERTAIN");throw blocked("Video submission requires reconciliation");}
        if("FAILED".equals(videoStatus)||"RECONCILIATION_REQUIRED".equals(videoStatus))throw blocked("Video step is not resumable without reconciliation");
        if("NOT_STARTED".equals(videoStatus)){
            state.beginVideo(shot.shotId());VideoSubmission submission;
            try{submission=operations.submitVideo(shot,vault.require(key(shot,"keyframe")));}
            catch(Exception error){providerFailure(shot.shotId(),"videoStatus","VIDEO_SUBMISSION_UNCERTAIN",error);throw error;}
            state.videoSubmitted(shot.shotId(),submission.requestId(),submission.taskId());checkpoint.after(shot.shotId()+":VIDEO_SUBMITTED");
            cursor=state.shot(shot.shotId());videoStatus=cursor.path("videoStatus").asText();
        }
        if("SUBMITTED".equals(videoStatus)){
            VideoResult result;
            try{result=operations.pollVideo(shot,cursor.path("videoTaskId").asText());}
            catch(ProviderException error){state.requireReconciliation(shot.shotId(),"VIDEO_POLL_UNKNOWN");throw error;}
            catch(Exception error){state.localFailure("VIDEO_LOCAL_VALIDATION_FAILED");throw error;}
            if(!"SUCCEEDED".equals(result.status())){
                if(Set.of("FAILED","CANCELLED","EXPIRED").contains(result.status())){state.localFailure("VIDEO_PROVIDER_"+result.status());throw new WorkflowException("PIPELINE_CANARY_VIDEO_FAILED","Video Provider ended with "+result.status());}
                state.requireReconciliation(shot.shotId(),"VIDEO_FINAL_STATUS_"+result.status());throw blocked("Video final status is not definitively successful");
            }
            requireProviderUrl(result.providerUrl());vault.put(key(shot,"video"),result.providerUrl());
            state.videoSucceeded(shot.shotId(),result.artifactId(),host(result.providerUrl()),fingerprint(result.providerUrl()),result.actualDurationMs());checkpoint.after(shot.shotId()+":VIDEO_SUCCEEDED");
        }
        cursor=state.shot(shot.shotId());String ttsStatus=cursor.path("ttsStatus").asText();PipelineCanaryFixture.Dialogue dialogue=fixture.dialogueFor(shot.shotId());
        if(dialogue==null){if(!"SUCCEEDED".equals(ttsStatus))state.ttsNotRequired(shot.shotId());}
        else if("SUBMITTING".equals(ttsStatus)){state.requireReconciliation(shot.shotId(),"TTS_SUBMISSION_UNCERTAIN");throw blocked("TTS submission requires reconciliation");}
        else if(!"SUCCEEDED".equals(ttsStatus)){
            state.beginTts(shot.shotId());AudioResult result;
            try{result=operations.tts(dialogue);}catch(Exception error){providerFailure(shot.shotId(),"ttsStatus","TTS_SUBMISSION_FAILED",error);throw error;}
            state.ttsSucceeded(shot.shotId(),result.requestId(),result.artifactId());checkpoint.after(shot.shotId()+":TTS_SUCCEEDED");
        }
    }

    private void providerFailure(String shotId,String field,String code,Exception error){
        if(error instanceof ProviderException provider&&provider.uncertain())state.requireReconciliation(shotId,code);else state.stepFailed(shotId,field,code);
    }
    private void writeEvidence(ObjectNode finalState,TimelineResult timeline,RenderResult render,QaResult qa) throws Exception {
        ObjectNode evidence=obj().put("runId",finalState.path("runId").asText()).put("commit",finalState.path("commit").asText())
                .put("generationProfile","TEST").put("status","SUCCEEDED").put("automaticRetry",false).put("automaticSecondTake",false);
        ObjectNode story=evidence.putObject("story").put("title",fixture.title());story.set("characters",mapper.valueToTree(fixture.characters()));story.set("props",mapper.valueToTree(fixture.props()));story.set("dialogues",mapper.valueToTree(fixture.dialogues()));
        evidence.set("scene",mapper.valueToTree(fixture.locations().getFirst()));ArrayNode shots=evidence.putArray("shots"),paid=evidence.putArray("paidRequests"),artifacts=evidence.putArray("artifacts");
        for(PipelineCanaryFixture.ShotPlan plan:fixture.shots()){
            ObjectNode shot=state.shot(plan.shotId());ObjectNode shotEvidence=mapper.valueToTree(plan);shotEvidence.set("state",shot.deepCopy());shots.add(shotEvidence);
            paid.add(request("IMAGE",shot.path("keyframeRequestId").asText(),"",operations.live()));paid.add(request("VIDEO",shot.path("videoRequestId").asText(),shot.path("videoTaskId").asText(),operations.live()));
            artifacts.add(provenance("KEYFRAME",shot.path("keyframeArtifactId").asText(),IMAGE_MODEL,"2K","",0,0,shot.path("keyframeRequestId").asText(),"","",plan));
            artifacts.add(provenance("VIDEO",shot.path("videoArtifactId").asText(),VIDEO_MODEL,"","480p",5,shot.path("actualDurationMs").asLong(),shot.path("videoRequestId").asText(),shot.path("videoTaskId").asText(),shot.path("keyframeArtifactId").asText(),plan));
            if(fixture.dialogueFor(plan.shotId())!=null){paid.add(request("TTS",shot.path("audioRequestId").asText(),"",operations.live()));artifacts.add(provenance("AUDIO",shot.path("audioArtifactId").asText(),"configured-seed-audio","","",0,0,shot.path("audioRequestId").asText(),"","",plan));}
        }
        evidence.set("artifacts",artifacts);evidence.set("timeline",mapper.valueToTree(timeline));
        ObjectNode renderNode=evidence.putObject("render").put("artifactId",render.artifactId()).put("fileName",render.output().getFileName().toString()).put("width",render.width()).put("height",render.height()).put("durationMs",render.durationMs()).put("fps",render.fps()).put("hasAudio",render.hasAudio()).put("subtitlePresent",render.subtitlePresent());
        ObjectNode qaNode=evidence.putObject("qa").put("passed",qa.passed());qaNode.set("failureCodes",mapper.valueToTree(qa.failureCodes()));
        int realMultiplier=operations.live()?1:0;evidence.set("budget",obj().put("realImageRequests",4*realMultiplier).put("realVideoSubmissions",4*realMultiplier).put("realAudioRequests",2*realMultiplier).put("realLlmRequests",0).put("realVlmRequests",0).put("billing","UNPRICED"));
        Path parent=evidencePath.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);mapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath.toFile(),evidence);
    }
    private ObjectNode request(String type,String requestId,String taskId,boolean live){return obj().put("type",type).put("providerRequestId",requestId).put("providerTaskId",taskId).put("real",live);}
    private ObjectNode provenance(String type,String artifactId,String modelId,String imageSize,String resolution,int requestedDuration,long actualDuration,String requestId,String taskId,String sourceArtifactId,PipelineCanaryFixture.ShotPlan shot){
        return obj().put("type",type).put("artifactId",artifactId).put("generationProfile","TEST").put("modelId",modelId).put("imageSize",imageSize).put("resolution",resolution)
                .put("requestedDuration",requestedDuration).put("actualDuration",actualDuration).put("providerRequestId",requestId).put("providerTaskId",taskId).put("sourceArtifactId",sourceArtifactId)
                .put("previousShotId",shot.previousShotId()).put("continuitySnapshotId",shot.continuitySnapshotId()).put("promptVersion",shot.promptVersion());
    }
    private String key(PipelineCanaryFixture.ShotPlan shot,String kind){return shot.shotId()+"."+kind+"ProviderUrl";}
    private void requireProviderUrl(String value){try{URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException();}catch(RuntimeException error){throw new WorkflowException("PIPELINE_CANARY_PROVIDER_URL_INVALID","Provider returned an invalid media URL");}}
    private String host(String value){return URI.create(value).getHost();}
    private String fingerprint(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private WorkflowException blocked(String message){return new WorkflowException("PIPELINE_CANARY_RECONCILIATION_REQUIRED",message);}

    public interface Operations {
        StoryIds plan(PipelineCanaryFixture fixture) throws Exception;
        KeyframeResult keyframe(PipelineCanaryFixture.ShotPlan shot) throws Exception;
        VideoSubmission submitVideo(PipelineCanaryFixture.ShotPlan shot,String firstFrameProviderUrl) throws Exception;
        VideoResult pollVideo(PipelineCanaryFixture.ShotPlan shot,String taskId) throws Exception;
        AudioResult tts(PipelineCanaryFixture.Dialogue dialogue) throws Exception;
        TimelineResult timeline(PipelineCanaryFixture fixture,ObjectNode state,PipelineCanaryArtifactVault vault) throws Exception;
        RenderResult render(TimelineResult timeline) throws Exception;
        QaResult finalQa(RenderResult render,TimelineResult timeline) throws Exception;
        default boolean live(){return false;}
    }
    @FunctionalInterface public interface Checkpoint {Checkpoint NONE=value->{};void after(String value);}
    public record StoryIds(String projectId,String episodeId,String sceneId) {}
    public record KeyframeResult(String providerUrl,String requestId,String artifactId,String modelId,boolean simulated) {}
    public record VideoSubmission(String requestId,String taskId,boolean simulated) {}
    public record VideoResult(String status,String providerUrl,String artifactId,long actualDurationMs,String modelId,boolean simulated) {}
    public record AudioResult(String requestId,String artifactId,String modelId,long actualDurationMs,boolean simulated) {}
    public record TimelineResult(String artifactId,int videoClips,int dialogueClips,int subtitleCues,int ambienceClips,long durationMs) {}
    public record RenderResult(String artifactId,Path output,int width,int height,long durationMs,double fps,boolean hasAudio,boolean subtitlePresent) {}
    public record QaResult(boolean passed,List<String> failureCodes) {public QaResult{failureCodes=List.copyOf(failureCodes);}}
    public record Result(ObjectNode state,TimelineResult timeline,RenderResult render,QaResult qa,Path evidence) {}
}
