package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.storage.*;
import com.yourapp.drama.workflow.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Component
public class GenerationWorker {
    private final DocumentStore store; private final JobService jobs; private final WorkflowService workflow;
    private final ImageGenerator images; private final VideoGenerator videos; private final LlmGateway llm;
    private final MediaStorage storage; private final ProviderMediaFetcher fetcher; private final ObjectMapper mapper;
    private final boolean enabled; private final AtomicBoolean ticking=new AtomicBoolean();
    private final CreativeJobs creative;
    private final PostProductionJobs post;
    private final AssetViewService assetViews;
    private final MediaProbeService mediaProbe;
    public GenerationWorker(DocumentStore store,JobService jobs,WorkflowService workflow,ImageGenerator images,VideoGenerator videos,LlmGateway llm,
                            MediaStorage storage,ProviderMediaFetcher fetcher,ObjectMapper mapper,CreativeJobs creative,PostProductionJobs post,AssetViewService assetViews,MediaProbeService mediaProbe,@Value("${drama.jobs.enabled:true}")boolean enabled){
        this.store=store;this.jobs=jobs;this.workflow=workflow;this.images=images;this.videos=videos;this.llm=llm;this.storage=storage;this.fetcher=fetcher;this.mapper=mapper;this.creative=creative;this.post=post;this.assetViews=assetViews;this.mediaProbe=mediaProbe;this.enabled=enabled;
    }
    @EventListener(ApplicationReadyEvent.class) public void recover(){
        if(!enabled)return;
        for(ObjectNode job:store.list(GENERATION_JOB,null,null))if(text(job,"status").equals("RUNNING")){
            if(Set.of("VIDEO","LIPSYNC").contains(text(job,"type"))&&!text(job,"providerTaskId").isBlank())continue;
            boolean uncertain=Set.of("VIDEO","KEYFRAME","STORYBOARD","ASSET_IMAGE","TTS","LIPSYNC","STORY","SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL","KEYFRAME_QC","VIDEO_QC").contains(text(job,"type"));
            ObjectNode failed=jobs.fail(id(job),"PROCESS_INTERRUPTED",uncertain?"进程中断，服务商可能已接受请求；请核对控制台后恢复":"进程中断，可重试局部任务",!uncertain,uncertain);creative.syncDevelopmentFailure(failed);assetViews.syncFailure(failed);
        }
    }
    @Scheduled(fixedDelayString="${drama.jobs.poll-delay-ms:1500}") public void scheduledTick(){if(enabled)tick();}
    public void tick(){
        if(!ticking.compareAndSet(false,true))return;
        try{
            for(ObjectNode job:store.list(GENERATION_JOB,null,null))if(text(job,"status").equals("RUNNING")&&Set.of("VIDEO","LIPSYNC").contains(text(job,"type"))&&!text(job,"providerTaskId").isBlank())poll(job);
            jobs.claim().ifPresent(this::process);
        }finally{ticking.set(false);}
    }
    public void process(ObjectNode job){
        try{
            if(store.get(GENERATION_JOB,id(job)).path("cancelRequested").asBoolean()){jobs.mutate(id(job),j->j.put("status","CANCELLED"));return;}
            creative.checkProductionInput(job);
            if(Set.of("DIRECTOR_PLAN","SHOT_DETAIL","KEYFRAME","STORYBOARD","VIDEO").contains(text(job,"type")))workflow.checkReferenceSnapshot(job.path("inputSnapshot"));
            switch(text(job,"type")){
                case "ASSET_IMAGE" -> assetViews.process(job);
                case "KEYFRAME","STORYBOARD" -> image(job);
                case "VIDEO","LIPSYNC" -> video(job);
                case "ARCHIVE" -> archive(job);
                case "TTS","TIMELINE","RENDER" -> post.process(job);
                default -> creative.process(job);
            }
        }catch(ProviderException e){
            if(e.requestId()!=null&&!e.requestId().isBlank())jobs.mutate(id(job),j->j.put("providerRequestId",e.requestId()));
            if(e.rawOutput()!=null)jobs.mutate(id(job),j->j.put("providerOutputRaw",boundedRaw(e.rawOutput())));
            if(e.finishReason()!=null)jobs.mutate(id(job),j->{j.put("finishReason",e.finishReason());j.set("providerUsage",usage(e.promptTokens(),e.completionTokens(),e.totalTokens()));});
            failed(job,e.code(),safeError(e),e.retryable(),e.uncertain());
        }
        catch(WorkflowException e){failed(job,e.code(),e.getMessage(),false,false);}
        catch(DataIntegrityViolationException e){failed(job,"DATA_CONFLICT","数据与现有记录冲突，请检查关联对象、版本或重复提交",false,false);}
        catch(Exception e){failed(job,"GENERATION_FAILED",e instanceof IllegalArgumentException?e.getMessage():"任务执行失败（"+e.getClass().getSimpleName()+"），请检查配置和输入",false,false);}
    }
    private void image(ObjectNode job){
        JsonNode input=job.path("inputSnapshot");
        ImageGenerator.ImageResult result=images.generate(new ImageGenerator.ImageRequest(required(input,"prompt"),strings(input.path("referenceImageUrls")),map(input.path("providerOptions"))));
        ResourceKind kind=text(job,"type").equals("KEYFRAME")?KEYFRAME:STORYBOARD;
        try { store.transaction(()->{
            ObjectNode asset=obj().put("projectId",project(job)).put("shotId",required(job,"shotId")).put("version",input.path("version").asInt(1))
                .put("attemptNo",input.path("version").asInt(1))
                .put("provider","VOLCENGINE").put("sourceModel",result.model()).put("providerUrl",result.providerUrl())
                .put("generationJobId",id(job)).put("providerRequestId",result.requestId()).put("promptVersionId",required(input,"promptVersionId"))
                .put("handoffStatus","READY").put("qcStatus","PENDING").put("locked",false).put("selected",false).put("simulated",result.simulated());
            if(result.expiresAt()!=null)asset.put("providerUrlExpiresAt",result.expiresAt().toString());
            if(result.simulated())asset.put("previewUrl","/demo/keyframe.svg");
            JsonNode plannedShot=input.path("context").path("shot");for(String field:List.of("directorPlanVersion","dramaticBeatVersion","shotPlanVersion"))if(plannedShot.has(field))asset.set(field,plannedShot.path(field).deepCopy());
            for(String field:List.of("sequenceCompilerVersion","normalizedPromptHash","referenceBindingsHash","referenceAuthorityFingerprint","continuitySnapshotHash","sequenceStateFingerprint","providerCapabilitiesVersion"))if(input.has(field))asset.set(field,input.path(field).deepCopy());
            asset.set("assetViewIds",input.path("assetViewIds").deepCopy());asset.set("assetReferences",input.path("assetReferences").deepCopy());
            if(kind==KEYFRAME&&input.path("storyboardReference").isObject())asset.set("storyboardReference",input.path("storyboardReference").deepCopy());
            asset.set("generationInputSnapshot",input.deepCopy());
            if(input.has("regeneratedFromId"))asset.set("regeneratedFromId",input.path("regeneratedFromId"));
            ObjectNode saved=store.create(kind,asset);
            jobs.mutate(id(job),j->j.put("providerRequestId",result.requestId()).put("simulated",result.simulated()));
            boolean cancelled=store.get(GENERATION_JOB,id(job)).path("cancelRequested").asBoolean();
            if(cancelled)jobs.mutate(id(job),j->j.put("status","CANCELLED"));else jobs.succeed(id(job),obj().put("assetId",id(saved)).put("kind",kind.path()).put("simulated",result.simulated()));
            ObjectNode shot=store.getForUpdate(SHOT,required(job,"shotId"));workflow.setShot(shot,cancelled?"NEEDS_REPAIR":kind==KEYFRAME?"KEYFRAME_READY":"STORYBOARD_READY");return null;
        }); }catch(Exception failure){
            ObjectNode accepted=obj().put("providerUrl",result.providerUrl()).put("model",result.model()).put("providerRequestId",result.requestId());
            if(result.expiresAt()!=null)accepted.put("providerUrlExpiresAt",result.expiresAt().toString());
            jobs.mutate(id(job),j->{j.put("providerRequestId",result.requestId());j.set("outputSnapshot",accepted);});
            jobs.fail(id(job),"ACCEPTED_PERSISTENCE_FAILED","图片已生成但本地保存失败，已保留返回信息，请先恢复记录，禁止重复提交",false,true);
            throw new WorkflowException("ACCEPTED_PERSISTENCE_FAILED","图片已生成，本地保存失败；请核对现有结果");
        }
    }
    private void video(ObjectNode job){
        JsonNode input=job.path("inputSnapshot");boolean lipsync="LIPSYNC".equals(text(job,"type"));ObjectNode frame=store.get(KEYFRAME,required(input,lipsync?"sourceKeyframeId":"keyframeId"));
        if(!lipsync&&expired(frame)){
            store.update(KEYFRAME,id(frame),revision(frame),frame.deepCopy().put("handoffStatus","EXPIRED"));
            throw new WorkflowException("PROVIDER_URL_EXPIRED","原始链接在排队期间过期，请用原提示词重画并重新质检");
        }
        if(!lipsync)workflow.checkHandoff(frame);
        String url=lipsync?"":required(input,"firstFrameProviderUrl");
        if(!lipsync&&!url.equals(required(frame,"providerUrl")))throw new WorkflowException("URL_SNAPSHOT_MISMATCH","关键帧生产链接与任务快照不一致");
        // The exact String returned by Seedream is the first frame. No storage call occurs on this path.
        List<VideoGenerator.Reference> references=new ArrayList<>();for(JsonNode ref:input.path("references"))references.add(new VideoGenerator.Reference(required(ref,"type"),required(ref,"url"),required(ref,"role")));
        VideoGenerator.Submission submission=videos.submit(new VideoGenerator.VideoRequest(required(input,"prompt"),lipsync?null:url,references,map(input.path("providerOptions"))));
        jobs.mutate(id(job),j->j.put("providerTaskId",submission.taskId()).put("providerRequestId",submission.requestId()).put("providerAcceptedAt",Instant.now().toString()).put("simulated",submission.simulated()));
        try{
            store.transaction(()->{
                ObjectNode take=obj().put("projectId",project(job)).put("shotId",required(job,"shotId")).put("takeNo",input.path("takeNo").asInt(1))
                    .put("provider","VOLCENGINE").put("sourceKeyframeId",id(frame)).put("sourceProviderUrlSnapshot",required(frame,"providerUrl"))
                    .put("promptVersionId",required(input,"promptVersionId")).put("generationJobId",id(job)).put("providerRequestId",submission.requestId()).put("providerTaskId",submission.taskId())
                    .put("providerStatus","QUEUED").put("qcStatus","PENDING").put("selected",false).put("locked",false).put("simulated",submission.simulated());
                for(String field:List.of("parentTakeId","continuationDepth","reanchorReason","sequenceStrategy","sequenceRelation","sequenceCompilerVersion","normalizedPromptHash","referenceBindingsHash","referenceAuthorityFingerprint","continuitySnapshotHash","sequenceStateFingerprint","providerCapabilitiesVersion"))if(input.has(field))take.set(field,input.path(field).deepCopy());
                if(input.path("retake").isObject())take.set("retakeAudit",input.path("retake").deepCopy());
                if(lipsync)take.put("variantType","LIPSYNC").put("sourceVideoTakeId",required(input,"sourceTakeId"));
                take.set("inputSnapshot",input.deepCopy());take.set("assetViewIds",frame.path("assetViewIds").deepCopy());take.set("assetReferences",frame.path("assetReferences").deepCopy()); ObjectNode saved=store.create(VIDEO_TAKE,take);
                if(!lipsync){ObjectNode current=store.getForUpdate(KEYFRAME,id(frame));store.update(KEYFRAME,id(current),revision(current),current.deepCopy().put("handoffStatus","HANDED_OFF").put("handedOffAt",Instant.now().toString()));}
                jobs.mutate(id(job),j->{j.put("providerTaskId",submission.taskId()).put("providerRequestId",submission.requestId()).put("progress",20).put("simulated",submission.simulated());j.set("outputSnapshot",obj().put("takeId",id(saved)));});
                if(!lipsync)jobs.enqueue(project(job),required(job,"shotId"),"ARCHIVE",obj().put("targetKind","keyframes").put("targetId",id(frame)).put("providerUrl",url).put("simulated",submission.simulated()),"archive:keyframe:"+id(frame));
                return null;
            });
        }catch(Exception e){jobs.fail(id(job),"ACCEPTED_PERSISTENCE_FAILED","服务商已接受视频任务，任务号 "+submission.taskId()+"；本地保存失败，请核对后恢复",false,true);throw new WorkflowException("SUBMISSION_UNCERTAIN","服务商已接单，本地记录保存失败，禁止自动重复提交");}
    }
    private void poll(ObjectNode stale){
        ObjectNode job=store.get(GENERATION_JOB,id(stale));String taskId=required(job,"providerTaskId");
        if(job.hasNonNull("nextPollAt")&&Instant.parse(text(job,"nextPollAt")).isAfter(Instant.now()))return;
        try{
            if(job.path("cancelRequested").asBoolean()&&!job.path("cancelSent").asBoolean()){
                try {
                    videos.cancel(taskId);jobs.mutate(id(job),j->j.put("cancelSent",true));
                } catch (ProviderException cancelError) {
                    if(!"CANCEL_NOT_ALLOWED".equals(cancelError.code())) throw cancelError;
                    jobs.mutate(id(job),j->j.put("cancelSent",true).put("lastPollError",safeError(cancelError)).put("lastPollAt",Instant.now().toString()));
                }
            }
            VideoGenerator.VideoTask result=videos.poll(taskId);
            String takeId=required(job.path("outputSnapshot"),"takeId");ObjectNode take=store.get(VIDEO_TAKE,takeId);
            if(result.status()==VideoGenerator.Status.QUEUED||result.status()==VideoGenerator.Status.RUNNING){
                jobs.mutate(id(job),j->j.put("progress",result.status()==VideoGenerator.Status.QUEUED?25:60).put("pollErrorCount",0).put("lastSuccessfulPollAt",Instant.now().toString()).remove("nextPollAt"));return;
            }
            if(result.status()==VideoGenerator.Status.SUCCEEDED){
                if(result.providerUrl()==null||result.providerUrl().isBlank())throw new WorkflowException("EMPTY_VIDEO_RESULT","服务商未返回视频地址");
                store.transaction(()->{
                    ObjectNode latest=store.getForUpdate(VIDEO_TAKE,takeId);ObjectNode next=latest.deepCopy().put("videoUrl",result.providerUrl()).put("providerStatus","SUCCEEDED");
                    if(result.expiresAt()!=null)next.put("providerUrlExpiresAt",result.expiresAt().toString());store.update(VIDEO_TAKE,takeId,revision(latest),next);
                    jobs.succeed(id(job),obj().put("takeId",takeId).put("simulated",result.simulated()));
                    ObjectNode shot=store.getForUpdate(SHOT,required(job,"shotId"));workflow.setShot(shot,"VIDEO_READY");
                    jobs.enqueue(project(job),required(job,"shotId"),"ARCHIVE",obj().put("targetKind","video-takes").put("targetId",takeId).put("providerUrl",result.providerUrl()).put("simulated",result.simulated()),"archive:video:"+takeId);return null;
                });
            }else{
                store.update(VIDEO_TAKE,takeId,revision(take),take.deepCopy().put("providerStatus",result.status().name()));
                if(result.status()==VideoGenerator.Status.CANCELLED)jobs.mutate(id(job),j->j.put("status","CANCELLED"));
                else jobs.fail(id(job),result.errorCode()==null?"VIDEO_FAILED":result.errorCode(),result.errorMessage()==null?"视频生成失败，可局部重拍":redact(result.errorMessage()),false,false);
                ObjectNode shot=store.get(SHOT,required(job,"shotId"));workflow.setShot(shot,"NEEDS_REPAIR");
            }
        }catch(ProviderException e){
            // A query/cancel failure must never re-enqueue a paid creation request.
            pollFailure(job,taskId,e);
        }catch(Exception e){
            // Polling has no new billable submission. Normalize unexpected adapter/runtime
            // failures so they receive bounded backoff and eventual reconciliation.
            pollFailure(job,taskId,new ProviderException("POLL_RUNTIME_ERROR","轮询服务异常："+e.getClass().getSimpleName(),null,0,true,false));
        }
    }
    private void pollFailure(ObjectNode job,String taskId,ProviderException error){
        int count=job.path("pollErrorCount").asInt()+1;boolean terminal=!error.retryable()||count>=3;String message=safeError(error);
        if(!terminal){jobs.mutate(id(job),j->j.put("pollErrorCount",count).put("lastPollError",message).put("lastPollAt",Instant.now().toString()).put("nextPollAt",Instant.now().plusSeconds(Math.min(30,1L<<count)).toString()));return;}
        String takeId=text(job.path("outputSnapshot"),"takeId");
        if(!takeId.isBlank()){ObjectNode take=store.get(VIDEO_TAKE,takeId);store.update(VIDEO_TAKE,takeId,revision(take),take.deepCopy().put("providerStatus","RECONCILIATION_REQUIRED"));}
        jobs.fail(id(job),error.code(),message,false,false);
        jobs.mutate(id(job),j->j.put("pollErrorCount",count).put("lastPollError",message).put("lastPollAt",Instant.now().toString()).put("reconciliationRequired",true));
        if(job.hasNonNull("shotId")){ObjectNode shot=store.get(SHOT,required(job,"shotId"));workflow.setShot(shot,"NEEDS_REPAIR");}
    }
    private void archive(ObjectNode job){
        JsonNode input=job.path("inputSnapshot");ResourceKind kind=ResourceKind.fromPath(required(input,"targetKind"));String targetId=required(input,"targetId");ObjectNode target=store.get(kind,targetId);
        if(kind==KEYFRAME&&!"HANDED_OFF".equals(text(target,"handoffStatus")))throw new WorkflowException("HANDOFF_REQUIRED","视频任务创建成功后才能归档关键帧");
        String archive;
        if(input.path("simulated").asBoolean())archive=kind==KEYFRAME?"/demo/keyframe.svg":kind==VIDEO_TAKE?"/demo/take.mp4":"/demo/audio.wav";
        else try(InputStream source=fetcher.open(required(input,"providerUrl"))){String suffix=kind==KEYFRAME?".png":kind==VIDEO_TAKE?".mp4":".mp3";String contentType=kind==KEYFRAME?"image/png":kind==VIDEO_TAKE?"video/mp4":"audio/mpeg";archive=storage.put(kind.path()+"/"+targetId+suffix,source,contentType);}
        catch(IOException e){throw new UncheckedIOException(e);}
        ObjectNode metadata=null;if(kind==VIDEO_TAKE&&!input.path("simulated").asBoolean()){if(!archive.startsWith("/api/media/"))throw new WorkflowException("ARCHIVE_URL_INVALID","归档地址无法用于媒体探测");try(InputStream saved=storage.open(archive.substring("/api/media/".length()))){metadata=mediaProbe.probe(saved,".mp4");}catch(IOException e){throw new UncheckedIOException(e);}}
        String finalArchive=archive;ObjectNode finalMetadata=metadata;
        store.transaction(()->{ObjectNode latest=store.getForUpdate(kind,targetId),next=latest.deepCopy().put("archiveUrl",finalArchive).put("archiveStatus","SUCCEEDED");if(finalMetadata!=null){next.setAll(finalMetadata);next.put("mediaProbeStatus","SUCCEEDED");}store.update(kind,targetId,revision(latest),next);ObjectNode result=obj().put("archiveUrl",finalArchive);if(finalMetadata!=null)result.set("mediaMetadata",finalMetadata);jobs.succeed(id(job),result);return null;});
    }
    private void failed(ObjectNode job,String code,String reason,boolean retryable,boolean uncertain){
        ObjectNode saved=jobs.fail(id(job),code,redact(reason),retryable,uncertain);
        creative.syncDevelopmentFailure(saved);
        assetViews.syncFailure(saved);
        if(saved.path("submissionUncertain").asBoolean()&&!uncertain)return;
        if(job.hasNonNull("shotId")&&!text(job,"type").equals("ARCHIVE")&&!text(saved,"status").equals("RETRY_WAIT")){
            ObjectNode shot=store.get(SHOT,required(job,"shotId"));workflow.setShot(shot,code.equals("PROVIDER_URL_EXPIRED")?"PROVIDER_URL_EXPIRED":"NEEDS_REPAIR");
        }
    }
    private String safeError(ProviderException e){return redact(e.getMessage());}
    private String boundedRaw(String raw){
        int limit=65_536;if(raw.length()<=limit)return raw;
        String marker="\n...[truncated]...\n";int head=(limit-marker.length())/2;int tail=limit-marker.length()-head;
        return raw.substring(0,head)+marker+raw.substring(raw.length()-tail);
    }
    private ObjectNode usage(long prompt,long completion,long total){return obj().put("promptTokens",prompt).put("completionTokens",completion).put("totalTokens",total);}
    private String redact(String value){return value==null?"模型调用失败":value.replaceAll("https?://\\S+","[模型地址]").replaceAll("(?i)(bearer\\s+)[^\\s]+","$1[hidden]");}
    private List<String> strings(JsonNode list){List<String> result=new ArrayList<>();list.forEach(n->result.add(n.asText()));return result;}
    private Map<String,Object> map(JsonNode object){return mapper.convertValue(object,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
}
