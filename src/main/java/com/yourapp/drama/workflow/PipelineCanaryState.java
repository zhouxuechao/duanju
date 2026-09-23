package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.yourapp.drama.workflow.Documents.obj;

/** Durable, URL-free resume cursor for the four-shot Phase B pipeline canary. */
public final class PipelineCanaryState {
    private static final Set<String> TOP_STATUSES=Set.of("READY","STORY_PLANNING","STORY_READY","SHOT_1_RUNNING","SHOT_2_RUNNING","SHOT_3_RUNNING","SHOT_4_RUNNING","MEDIA_READY","TIMELINE_BUILDING","TIMELINE_READY","RENDERING","RENDER_READY","FINAL_QA","SUCCEEDED","FAILED","RECONCILIATION_REQUIRED");
    private static final Set<String> STEP_STATUSES=Set.of("NOT_STARTED","SUBMITTING","SUBMITTED","SUCCEEDED","FAILED","RECONCILIATION_REQUIRED");
    private final Path path;
    private final ObjectMapper mapper;
    private ObjectNode state;

    private PipelineCanaryState(Path path,ObjectMapper mapper,ObjectNode state){this.path=path;this.mapper=mapper;this.state=state;}

    public static PipelineCanaryState start(Path path,String runId,String commit,List<String> shotIds){
        if(Files.exists(path))throw new WorkflowException("PIPELINE_CANARY_STATE_EXISTS","Pipeline Canary state already exists; resume or reconcile it instead of starting again");
        if(shotIds==null||shotIds.size()!=4||new HashSet<>(shotIds).size()!=4)throw new IllegalArgumentException("Pipeline Canary requires four unique shots");
        ObjectMapper mapper=new ObjectMapper();String now=Instant.now().toString();
        ObjectNode value=obj().put("runId",safe(runId)).put("phase","PIPELINE").put("status","READY").put("currentStep","READY")
                .put("generationProfile","TEST").put("commit",safe(commit)).put("createdAt",now).put("updatedAt",now)
                .put("projectId","").put("episodeId","").put("sceneId","").put("timelineArtifactId","").put("renderArtifactId","");
        ArrayNode shots=value.putArray("shots");
        for(String shotId:shotIds)shots.add(obj().put("shotId",safe(shotId)).put("keyframeStatus","NOT_STARTED")
                .put("keyframeRequestId","").put("keyframeArtifactId","").put("keyframeProviderUrlHost","").put("keyframeProviderUrlFingerprint","")
                .put("videoStatus","NOT_STARTED").put("videoRequestId","").put("videoTaskId","").put("videoArtifactId","")
                .put("videoProviderUrlHost","").put("videoProviderUrlFingerprint","").put("actualDurationMs",0)
                .put("ttsStatus","NOT_STARTED").put("audioRequestId","").put("audioArtifactId","").put("timelineStatus","NOT_STARTED"));
        PipelineCanaryState result=new PipelineCanaryState(path,mapper,value);result.write(value);return result;
    }

    public static PipelineCanaryState open(Path path){
        try{
            ObjectMapper mapper=new ObjectMapper();ObjectNode value=(ObjectNode)mapper.readTree(Files.readString(path,StandardCharsets.UTF_8));
            if(!"PIPELINE".equals(value.path("phase").asText())||value.path("runId").asText().isBlank()||!TOP_STATUSES.contains(value.path("status").asText())||value.path("shots").size()!=4)
                throw new IllegalArgumentException("Pipeline Canary state is invalid");
            return new PipelineCanaryState(path,mapper,value);
        }catch(IOException error){throw new WorkflowException("PIPELINE_CANARY_STATE_UNAVAILABLE","Pipeline Canary state cannot be read");}
    }

    public synchronized ObjectNode snapshot(){return state.deepCopy();}
    public synchronized ObjectNode shot(String shotId){return findShot(shotId).deepCopy();}

    public synchronized void storyPlanning(){top("STORY_PLANNING","STORY_PLANNING");}
    public synchronized void storyReady(String projectId,String episodeId,String sceneId){
        ObjectNode next=state.deepCopy().put("projectId",safe(projectId)).put("episodeId",safe(episodeId)).put("sceneId",safe(sceneId));
        persist(next,"STORY_READY","STORY_READY");
    }
    public synchronized void beginKeyframe(String shotId){shotStep(shotId,"keyframeStatus","SUBMITTING",running(shotId),shotId+":KEYFRAME");}
    public synchronized void keyframeSucceeded(String shotId,String requestId,String artifactId,String host,String fingerprint){
        mutateShot(shotId,shot->shot.put("keyframeStatus","SUCCEEDED").put("keyframeRequestId",safe(requestId)).put("keyframeArtifactId",safe(artifactId))
                .put("keyframeProviderUrlHost",safeHost(host)).put("keyframeProviderUrlFingerprint",safe(fingerprint)),running(shotId),shotId+":KEYFRAME_SUCCEEDED");
    }
    public synchronized void beginVideo(String shotId){shotStep(shotId,"videoStatus","SUBMITTING",running(shotId),shotId+":VIDEO_SUBMITTING");}
    public synchronized void videoSubmitted(String shotId,String requestId,String taskId){
        mutateShot(shotId,shot->shot.put("videoStatus","SUBMITTED").put("videoRequestId",safe(requestId)).put("videoTaskId",safe(taskId)),running(shotId),shotId+":VIDEO_SUBMITTED");
    }
    public synchronized void videoSucceeded(String shotId,String artifactId,String host,String fingerprint,long actualDurationMs){
        if(actualDurationMs<=0)throw new IllegalArgumentException("Video duration must be positive");
        mutateShot(shotId,shot->shot.put("videoStatus","SUCCEEDED").put("videoArtifactId",safe(artifactId)).put("videoProviderUrlHost",safeHost(host))
                .put("videoProviderUrlFingerprint",safe(fingerprint)).put("actualDurationMs",actualDurationMs),running(shotId),shotId+":VIDEO_SUCCEEDED");
    }
    public synchronized void beginTts(String shotId){shotStep(shotId,"ttsStatus","SUBMITTING",running(shotId),shotId+":TTS_SUBMITTING");}
    public synchronized void ttsSucceeded(String shotId,String requestId,String audioArtifactId){
        mutateShot(shotId,shot->shot.put("ttsStatus","SUCCEEDED").put("audioRequestId",safe(requestId)).put("audioArtifactId",safe(audioArtifactId)),running(shotId),shotId+":TTS_SUCCEEDED");
    }
    public synchronized void ttsNotRequired(String shotId){shotStep(shotId,"ttsStatus","SUCCEEDED",running(shotId),shotId+":TTS_NOT_REQUIRED");}

    public synchronized void mediaReady(){top("MEDIA_READY","MEDIA_READY");}
    public synchronized void beginTimeline(){top("TIMELINE_BUILDING","TIMELINE_BUILDING");}
    public synchronized void timelineReady(String artifactId){
        ObjectNode next=state.deepCopy().put("timelineArtifactId",safe(artifactId));
        for(var item:next.withArray("shots"))((ObjectNode)item).put("timelineStatus","SUCCEEDED");
        persist(next,"TIMELINE_READY","TIMELINE_READY");
    }
    public synchronized void beginRender(){top("RENDERING","RENDERING");}
    public synchronized void renderReady(String artifactId){ObjectNode next=state.deepCopy().put("renderArtifactId",safe(artifactId));persist(next,"RENDER_READY","RENDER_READY");}
    public synchronized void beginFinalQa(){top("FINAL_QA","FINAL_QA");}
    public synchronized void succeeded(){top("SUCCEEDED","SUCCEEDED");}
    public synchronized void localFailure(String code){ObjectNode next=state.deepCopy().put("failureCode",safe(code));persist(next,"FAILED",state.path("currentStep").asText());}
    public synchronized void stepFailed(String shotId,String field,String code){
        if(!Set.of("keyframeStatus","videoStatus","ttsStatus").contains(field))throw new IllegalArgumentException("Unknown Pipeline Canary step field");
        mutateShot(shotId,shot->shot.put(field,"FAILED").put("failureCode",safe(code)),"FAILED",shotId+":"+field+":FAILED");
    }

    public synchronized void requireReconciliation(String shotId,String code){
        mutateShot(shotId,shot->{String field="SUBMITTING".equals(shot.path("keyframeStatus").asText())?"keyframeStatus":"videoStatus";shot.put(field,"RECONCILIATION_REQUIRED").put("reconciliationCode",safe(code));},"RECONCILIATION_REQUIRED",shotId+":RECONCILIATION_REQUIRED");
    }

    private void shotStep(String shotId,String field,String value,String topStatus,String currentStep){
        if(!STEP_STATUSES.contains(value))throw new IllegalArgumentException("Unknown step status");
        mutateShot(shotId,shot->shot.put(field,value),topStatus,currentStep);
    }
    private void mutateShot(String shotId,Consumer<ObjectNode> change,String topStatus,String currentStep){
        ObjectNode next=state.deepCopy();ObjectNode shot=findShot(next,shotId);change.accept(shot);persist(next,topStatus,currentStep);
    }
    private void top(String status,String currentStep){persist(state.deepCopy(),status,currentStep);}
    private void persist(ObjectNode next,String status,String currentStep){
        if(!TOP_STATUSES.contains(status))throw new IllegalArgumentException("Unknown Pipeline Canary status: "+status);
        next.put("status",status).put("currentStep",safe(currentStep)).put("updatedAt",Instant.now().toString());write(next);state=next;
    }
    private ObjectNode findShot(String shotId){return findShot(state,shotId);}
    private ObjectNode findShot(ObjectNode value,String shotId){
        for(var item:value.withArray("shots"))if(shotId.equals(item.path("shotId").asText()))return (ObjectNode)item;
        throw new IllegalArgumentException("Unknown Pipeline Canary shot: "+shotId);
    }
    private String running(String shotId){int index=0;for(var item:state.withArray("shots")){index++;if(shotId.equals(item.path("shotId").asText()))return "SHOT_"+index+"_RUNNING";}throw new IllegalArgumentException("Unknown Pipeline Canary shot: "+shotId);}
    private void write(ObjectNode value){
        try{
            Path parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);Path temp=path.resolveSibling(path.getFileName()+".tmp");
            Files.writeString(temp,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException error){throw new WorkflowException("PIPELINE_CANARY_STATE_UNAVAILABLE","Pipeline Canary state cannot be persisted");}
    }
    private static String safeHost(String value){String result=safe(value);if(result.contains("/")||result.contains(":")||result.contains("?"))throw new IllegalArgumentException("Pipeline Canary state host must not be a URL");return result;}
    private static String safe(String value){
        if(value==null)return "";String clean=value.replaceAll("[\\r\\n\\t]"," ").trim();
        if(clean.contains("://")||clean.contains("?")||clean.matches("(?i).*authorization.*|.*bearer\\s+.*|.*ark-[a-z0-9-]{10,}.*"))throw new IllegalArgumentException("Pipeline Canary state must not contain credentials or URLs");
        return clean.substring(0,Math.min(clean.length(),240));
    }
}
