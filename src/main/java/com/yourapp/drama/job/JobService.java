package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.JobType;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.workflow.WorkflowException;
import com.yourapp.drama.workflow.ModelRoutingPolicy;
import com.yourapp.drama.workflow.TestBudgetGuard;
import com.yourapp.drama.production.GenerationProfilePolicy;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class JobService {
    private final DocumentStore store;
    private final JobEvents events;
    private final ModelRoutingPolicy routing;
    private final TestBudgetGuard testBudget;
    private final GenerationProfilePolicy generationProfiles;
    public JobService(DocumentStore store,JobEvents events,ModelRoutingPolicy routing,TestBudgetGuard testBudget,GenerationProfilePolicy generationProfiles){this.store=store;this.events=events;this.routing=routing;this.testBudget=testBudget;this.generationProfiles=generationProfiles;}
    public ObjectNode enqueue(String projectId,String shotId,String type,JsonNode input,String requestKey){
        try{JobType.valueOf(type);}catch(IllegalArgumentException error){throw new WorkflowException("JOB_TYPE_INVALID","当前流程不支持任务类型："+type);}
        ObjectNode job=store.transaction(()->{
            ObjectNode projectDocument=store.getForUpdate(PROJECT,projectId);
            ObjectNode settings=generationProfiles.resolved(projectDocument),frozen=input.isObject()?((ObjectNode)input).deepCopy():obj();String generationProfile=settings.path("generationProfile").asText("TEST");frozen.putIfAbsent("generationProfile",com.fasterxml.jackson.databind.node.TextNode.valueOf(generationProfile));
            if(Set.of("STORYBOARD","KEYFRAME","ASSET_IMAGE").contains(type)){frozen.putIfAbsent("modelId",settings.path("imageModel"));frozen.putIfAbsent("imageSize",settings.path("imageSize"));}
            if(Set.of("VIDEO","LIPSYNC").contains(type)){frozen.putIfAbsent("modelId",settings.path("videoModel"));frozen.putIfAbsent("resolution",settings.path("videoResolution"));}
            if(requestKey!=null&&!requestKey.isBlank())for(ObjectNode old:store.list(GENERATION_JOB,projectId,null))if(requestKey.equals(text(old,"requestKey"))){
                if(!type.equals(text(old,"type"))||!Objects.equals(shotId,old.hasNonNull("shotId")?text(old,"shotId"):null)||!old.path("inputSnapshot").equals(frozen))throw new WorkflowException("IDEMPOTENCY_CONFLICT","此请求标识已用于不同操作或输入");
                return old;
            }
            enforceBudget(projectDocument,shotId,type,frozen);
            boolean reserved=testBudget.reserve(type,frozen);
            String localTaskId=UUID.randomUUID().toString();ObjectNode next=obj().put("id",localTaskId).put("localTaskId",localTaskId).put("projectId",projectId).put("type",type).put("status","QUEUED").put("phase","QUEUED").put("progress",0)
                .put("attempts",0).put("retryCount",0).put("maxAttempts",3).put("cost",0).put("costKnown",false).put("requestKey",requestKey==null?UUID.randomUUID().toString():requestKey).put("generationProfile",generationProfile);
            for(String field:List.of("modelId","resolution","imageSize"))if(frozen.hasNonNull(field))next.set(field,frozen.path(field).deepCopy());
            if(reserved)next.put("testBudgetReserved",true).put("testBudgetReservationStatus","RESERVED");
            if(shotId!=null)next.put("shotId",shotId);
            ObjectNode routingDecision=routing.decide(type,frozen.path("complexity").asText("MEDIUM"),frozen.path("qualityTier").asText("BALANCED"));if(frozen.hasNonNull("modelId"))routingDecision.put("plannedModel",text(frozen,"modelId")).put("chosenModel",text(frozen,"modelId"));next.set("routingDecision",routingDecision);next.put("provider",routingDecision.path("chosenProvider").asText()).put("model",routingDecision.path("chosenModel").asText()).put("plannedModel",routingDecision.path("plannedModel").asText()).put("qualityTier",routingDecision.path("qualityTier").asText());
            for(String field:List.of("compilerVersion","sequenceCompilerVersion","normalizedPromptHash","referenceBindingsHash","referenceAuthorityFingerprint","continuitySnapshotHash","sequenceStateFingerprint","providerCapabilitiesVersion","capabilityFingerprint","sequenceStrategy","sequenceRelation","parentTakeId","continuationDepth","reanchorReason","resolution"))if(frozen.has(field))next.set(field,frozen.path(field).deepCopy());
            next.set("inputSnapshot",frozen); next.set("outputSnapshot",obj());
            next.putArray("statusHistory").add(obj().put("to","QUEUED").put("at",java.time.Instant.now().toString()));
            try{return store.create(GENERATION_JOB,next);}catch(RuntimeException error){if(reserved)testBudget.release(type,frozen);throw error;}
        }); events.publish(job);return job;
    }
    private void enforceBudget(ObjectNode projectDocument,String shotId,String type,JsonNode input){
        double projectSpent=latestCosts(id(projectDocument)).stream().mapToDouble(c->c.path("actualCost").asDouble(c.path("estimatedCost").asDouble())).sum(),estimated=input.path("estimatedCost").asDouble(0),projectLimit=projectDocument.path("maxProjectBudget").asDouble(0);
        if(projectLimit>0&&projectSpent+estimated>projectLimit)throw new WorkflowException("PROJECT_BUDGET_EXCEEDED","项目预算已用 "+projectSpent+"，本次预计 "+estimated+"，上限 "+projectLimit);
        String episodeId=text(input,"episodeId");double episodeLimit=0;if(!episodeId.isBlank()){ObjectNode episode=store.get(EPISODE,episodeId);episodeLimit=episode.path("maxEpisodeBudget").asDouble(projectDocument.path("defaultEpisodeBudget").asDouble(0));double spent=latestCosts(id(projectDocument)).stream().filter(c->episodeId.equals(text(c,"episodeId"))).mapToDouble(c->c.path("actualCost").asDouble(c.path("estimatedCost").asDouble())).sum();if(episodeLimit>0&&spent+estimated>episodeLimit)throw new WorkflowException("EPISODE_BUDGET_EXCEEDED","本集预算已用 "+spent+"，本次预计 "+estimated+"，上限 "+episodeLimit);}
        if(shotId!=null&&Set.of("STORYBOARD","KEYFRAME","VIDEO","TTS","LIPSYNC").contains(type)){ObjectNode shot=store.get(SHOT,shotId);int max=shot.path("maxPaidRetries").asInt(projectDocument.path("defaultMaxPaidRetries").asInt(0)),submitted=0;for(ObjectNode old:store.list(GENERATION_JOB,id(projectDocument),null))if(shotId.equals(text(old,"shotId"))&&type.equals(text(old,"type"))&&(!text(old,"providerRequestId").isBlank()||Set.of("QUEUED","RUNNING","SUCCESS").contains(text(old,"status"))))submitted++;if(max>0&&submitted>=max)throw new WorkflowException("SHOT_PAID_RETRY_LIMIT_REACHED","本镜此类付费任务已达到 "+max+" 次上限，请先审查失败原因");}
    }
    public ObjectNode mutate(String id,Consumer<ObjectNode> changes){
        ObjectNode saved=store.transaction(()->{ObjectNode old=store.getForUpdate(GENERATION_JOB,id);ObjectNode next=old.deepCopy();changes.accept(next);recordTransition(old,next);return store.update(GENERATION_JOB,id,revision(old),next);});
        events.publish(saved);return saved;
    }
    public Optional<ObjectNode> claim(){return claim(null);}
    public Optional<ObjectNode> claim(String projectId){
        for(ObjectNode candidate:store.list(GENERATION_JOB,null,null)){
            if(projectId!=null&&!projectId.equals(project(candidate)))continue;
            String status=text(candidate,"status");
            if(!status.equals("QUEUED")&&!status.equals("RETRY_WAIT"))continue;
            if(candidate.hasNonNull("retryAt")&&Instant.parse(text(candidate,"retryAt")).isAfter(Instant.now()))continue;
            ObjectNode result=store.transaction(()->{
                ObjectNode current=store.getForUpdate(GENERATION_JOB,id(candidate));
                if(!Set.of("QUEUED","RETRY_WAIT").contains(text(current,"status")))return null;
                int attempts=current.path("attempts").asInt();ObjectNode next=current.deepCopy().put("status","RUNNING").put("phase","EXECUTING").put("startedAt",Instant.now().toString()).put("attempts",attempts+1).put("retryCount",Math.max(0,attempts)).put("progress",5);
                recordTransition(current,next);
                next.remove("failureReason");return store.update(GENERATION_JOB,id(current),revision(current),next);
            });
            if(result!=null){events.publish(result);return Optional.of(result);}
        }
        return Optional.empty();
    }
    public ObjectNode succeed(String id,JsonNode output){ObjectNode saved=mutate(id,j->{
        if(text(j,"status").equals("CANCELLED"))return;
        j.put("status","SUCCESS").put("phase","COMPLETED").put("progress",100).put("completedAt",Instant.now().toString()).put("elapsedMs",elapsedMs(j));j.set("outputSnapshot",output.deepCopy());
    });settleReservation(saved,false);recordTerminalCost(saved);return store.get(GENERATION_JOB,id);}
    public ObjectNode fail(String id,String code,String reason,boolean retryable,boolean uncertain){ObjectNode saved=mutate(id,j->{
        if(Set.of("CANCELLED","SUCCESS").contains(text(j,"status")))return;
        boolean unresolved=uncertain||j.path("submissionUncertain").asBoolean();
        boolean retry=retryable&&!unresolved&&!j.path("cancelRequested").asBoolean()&&j.path("attempts").asInt()<j.path("maxAttempts").asInt(3);
        j.put("status",retry?"RETRY_WAIT":"FAILED").put("phase",retry?"WAITING_RETRY":"FAILED").put("failureCode",code).put("failureReason",reason).put("elapsedMs",elapsedMs(j))
            .put("submissionUncertain",unresolved).put("retryable",retryable&&!unresolved);
        if(retry)j.put("retryAt",Instant.now().plusSeconds(5L*(1L<<Math.min(j.path("attempts").asInt(),5))).toString());
    });if("FAILED".equals(text(saved,"status")))settleFailedReservation(saved);recordTerminalCost(saved);return store.get(GENERATION_JOB,id);}
    public ObjectNode unknown(String id,String code,String reason){ObjectNode saved=mutate(id,j->{
        if(Set.of("SUCCESS","CANCELLED").contains(text(j,"status")))return;
        j.put("status","UNKNOWN").put("phase","RECONCILIATION").put("failureCode",code).put("failureReason",reason).put("elapsedMs",elapsedMs(j))
            .put("retryable",false).put("submissionUncertain",false).put("reconciliationRequired",true).put("billingStatus","POSSIBLY_BILLED");
    });recordUnknownCost(saved);return store.get(GENERATION_JOB,id);}
    public ObjectNode retry(String id){return store.transaction(()->{
        ObjectNode j=store.getForUpdate(GENERATION_JOB,id);
        if(!Set.of("FAILED","CANCELLED").contains(text(j,"status")))throw new WorkflowException("NOT_RETRYABLE","仅失败或取消的任务可以重试");
        if(j.path("submissionUncertain").asBoolean())throw new WorkflowException("SUBMISSION_UNCERTAIN","服务商可能已接受请求，请先在控制台核对任务，避免重复扣费");
        for(ObjectNode existing:store.list(GENERATION_JOB,project(j),null))if(id.equals(text(existing.path("inputSnapshot"),"retryOfJobId"))&&text(j,"type").equals(text(existing,"type"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCESS").contains(text(existing,"status")))return existing;
        if(!text(j,"providerRequestId").isBlank()&&!"SHOT_DETAIL".equals(text(j,"type"))&&!"CONFIRMED_NOT_SUBMITTED".equals(text(j,"reconciliationStatus")))throw new WorkflowException("NEW_TAKE_REQUIRED","服务商已创建任务，请使用重拍生成新版本");
        if(j.hasNonNull("shotId"))for(ObjectNode other:store.list(GENERATION_JOB,project(j),null))
            if(text(j,"shotId").equals(text(other,"shotId"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(other,"status"))&&!"ARCHIVE".equals(text(other,"type")))throw new WorkflowException("GENERATION_ACTIVE","此镜头已有生产任务，请等待完成");
        ObjectNode input=(ObjectNode)j.path("inputSnapshot").deepCopy();input.remove("reuseProviderOutput");input.put("retryOfJobId",id);
        return enqueue(project(j),j.hasNonNull("shotId")?text(j,"shotId"):null,text(j,"type"),input,"retry:"+id+":"+UUID.randomUUID());
    });}
    public ObjectNode reconcile(String id,ObjectNode request){
        ObjectNode saved=store.transaction(()->{
            ObjectNode old=store.getForUpdate(GENERATION_JOB,id);
            boolean unknown="UNKNOWN".equals(text(old,"status"))&&old.path("reconciliationRequired").asBoolean();
            if(!unknown&&(!"FAILED".equals(text(old,"status"))||!old.path("submissionUncertain").asBoolean()))throw new WorkflowException("RECONCILIATION_NOT_REQUIRED","只有提交状态不确定或服务商状态未知的任务需要对账");
            String decision=required(request,"decision").toUpperCase(Locale.ROOT),evidence=required(request,"evidence"),reviewer=required(request,"reviewer");
            if(!Set.of("CONFIRMED_SUBMITTED","CONFIRMED_NOT_SUBMITTED","UNRESOLVED").contains(decision))throw new WorkflowException("RECONCILIATION_DECISION_INVALID","对账结论无效");
            if(unknown&&"CONFIRMED_NOT_SUBMITTED".equals(decision))throw new WorkflowException("RECONCILIATION_DECISION_INVALID","任务已有服务商任务号，不能标记为未提交");
            ObjectNode next=old.deepCopy().put("reconciliationStatus",decision).put("reconciledAt",Instant.now().toString());
            next.withArray("reconciliationHistory").add(obj().put("decision",decision).put("evidence",evidence).put("reviewer",reviewer).put("at",Instant.now().toString()));
            if("CONFIRMED_SUBMITTED".equals(decision)){
                if(!Set.of("VIDEO","LIPSYNC").contains(text(old,"type")))throw new WorkflowException("RECONCILIATION_RESUME_UNSUPPORTED","此类任务没有可安全轮询的服务商任务号，不能假定成功后继续");
                String taskId=text(request,"providerTaskId");if(taskId.isBlank())taskId=text(old,"providerTaskId");
                if(taskId.isBlank())throw new WorkflowException("PROVIDER_TASK_ID_REQUIRED","确认服务商已接单时必须填写服务商任务号");
                String takeId=ensureRecoveredTake(old,request,taskId);
                next.put("providerTaskId",taskId).put("status","RUNNING").put("phase","PROVIDER_POLLING").put("progress",20).put("submissionUncertain",false).put("retryable",false).put("reconciliationRequired",false).put("resumedAt",Instant.now().toString());
                next.set("outputSnapshot",obj().put("takeId",takeId));next.remove(List.of("failureReason","retryAt"));
            }else if("CONFIRMED_NOT_SUBMITTED".equals(decision)){
                next.put("submissionUncertain",false).put("retryable",true).put("reconciliationRequired",false);
            }else{
                next.put("submissionUncertain",true).put("retryable",false).put("reconciliationRequired",true);
            }
            recordTransition(old,next);return store.update(GENERATION_JOB,id,revision(old),next);
        });events.publish(saved);if("CONFIRMED_NOT_SUBMITTED".equals(text(saved,"reconciliationStatus")))releaseReservation(saved,"CONFIRMED_NOT_SUBMITTED");return store.get(GENERATION_JOB,id);
    }
    private String ensureRecoveredTake(ObjectNode job,ObjectNode request,String taskId){
        String takeId=text(request,"videoTakeId");if(takeId.isBlank())takeId=text(job.path("outputSnapshot"),"takeId");
        if(!takeId.isBlank()){store.get(VIDEO_TAKE,takeId);return takeId;}
        JsonNode input=job.path("inputSnapshot");boolean lipsync="LIPSYNC".equals(text(job,"type"));
        String frameId=text(input,lipsync?"sourceKeyframeId":"keyframeId");
        if(frameId.isBlank()||!job.hasNonNull("shotId")||text(input,"promptVersionId").isBlank())throw new WorkflowException("RECONCILIATION_INPUT_INCOMPLETE","本地缺少恢复视频轮询所需的镜头、关键帧或提示词版本");
        ObjectNode frame=store.get(KEYFRAME,frameId);
        ObjectNode take=obj().put("projectId",project(job)).put("shotId",text(job,"shotId")).put("takeNo",input.path("takeNo").asInt(1))
                .put("provider",request.path("provider").asText("VOLCENGINE")).put("sourceKeyframeId",frameId).put("sourceProviderUrlSnapshot",required(frame,"providerUrl"))
                .put("promptVersionId",text(input,"promptVersionId")).put("generationJobId",id(job)).put("providerRequestId",text(job,"providerRequestId"))
                .put("providerTaskId",taskId).put("providerStatus","QUEUED").put("qcStatus","PENDING").put("selected",false).put("locked",false).put("simulated",job.path("simulated").asBoolean()).put("recoveredFromJobId",id(job));
        for(String field:List.of("parentTakeId","continuationDepth","reanchorReason","sequenceStrategy","sequenceRelation","sequenceCompilerVersion","normalizedPromptHash","referenceBindingsHash","referenceAuthorityFingerprint","continuitySnapshotHash","sequenceStateFingerprint","providerCapabilitiesVersion","capabilityFingerprint"))if(input.has(field))take.set(field,input.path(field).deepCopy());
        if(input.path("retake").isObject())take.set("retakeAudit",input.path("retake").deepCopy());
        if(lipsync)take.put("variantType","LIPSYNC").put("sourceVideoTakeId",text(input,"sourceTakeId"));
        take.set("inputSnapshot",input.deepCopy());take.set("assetViewIds",frame.path("assetViewIds").deepCopy());take.set("assetReferences",frame.path("assetReferences").deepCopy());
        return id(store.create(VIDEO_TAKE,take));
    }
    public ObjectNode revalidate(String id){return store.transaction(()->{
        ObjectNode j=store.getForUpdate(GENERATION_JOB,id);
        if(!Set.of("FAILED","CANCELLED").contains(text(j,"status")))throw new WorkflowException("NOT_REVALIDATABLE","仅失败或取消的任务可以本地复核");
        if(!j.path("providerOutput").isObject()||j.path("providerOutput").isEmpty())throw new WorkflowException("NO_VALIDATED_PROVIDER_OUTPUT","任务没有可本地复核的完整结构化输出");
        for(ObjectNode existing:store.list(GENERATION_JOB,project(j),null))if(id.equals(text(existing.path("inputSnapshot"),"revalidationOfJobId"))&&text(j,"type").equals(text(existing,"type"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCESS").contains(text(existing,"status")))return existing;
        ObjectNode input=(ObjectNode)j.path("inputSnapshot").deepCopy();input.put("retryOfJobId",id).put("revalidationOfJobId",id).put("reuseProviderOutput",true);
        return enqueue(project(j),j.hasNonNull("shotId")?text(j,"shotId"):null,text(j,"type"),input,"revalidate:"+id+":"+UUID.randomUUID());
    });}
    public ObjectNode cancel(String id){ObjectNode saved=mutate(id,j->{
        if(Set.of("SUCCESS","FAILED","CANCELLED").contains(text(j,"status")))return;
        j.put("cancelRequested",true);
        if(!text(j,"status").equals("RUNNING"))j.put("status","CANCELLED").put("phase","CANCELLED").put("completedAt",Instant.now().toString()).put("elapsedMs",elapsedMs(j));
    });if("CANCELLED".equals(text(saved,"status"))&&text(saved,"providerRequestId").isBlank()&&text(saved,"providerTaskId").isBlank())releaseReservation(saved,"CANCELLED_BEFORE_SUBMIT");return store.get(GENERATION_JOB,id);}
    public ObjectNode cancelled(String id){return mutate(id,j->j.put("status","CANCELLED").put("phase","CANCELLED").put("completedAt",Instant.now().toString()).put("elapsedMs",elapsedMs(j)));}
    private void recordTransition(ObjectNode old,ObjectNode next){
        String from=text(old,"status"),to=text(next,"status");if(from.equals(to))return;
        List<String> path;
        if(from.equals("RUNNING")&&to.equals("RETRY_WAIT"))path=List.of("FAILED","RETRY_WAIT");
        else if(from.equals("RETRY_WAIT")&&to.equals("RUNNING"))path=List.of("QUEUED","RUNNING");
        else if(Set.of("FAILED","UNKNOWN","WAITING_HUMAN").contains(from)&&to.equals("RUNNING")&&"CONFIRMED_SUBMITTED".equals(text(next,"reconciliationStatus")))path=List.of("RUNNING");
        else if((from.equals("QUEUED")&&Set.of("RUNNING","CANCELLED").contains(to)) ||
                (from.equals("RUNNING")&&Set.of("SUCCESS","FAILED","CANCELLED").contains(to)) ||
                (from.equals("RUNNING")&&Set.of("UNKNOWN","WAITING_HUMAN").contains(to)) ||
                (from.equals("UNKNOWN")&&Set.of("WAITING_HUMAN","CANCELLED").contains(to)) ||
                (from.equals("WAITING_HUMAN")&&Set.of("FAILED","CANCELLED").contains(to)) ||
                (from.equals("RETRY_WAIT")&&to.equals("CANCELLED")))path=List.of(to);
        else throw new WorkflowException("ILLEGAL_JOB_TRANSITION","任务状态不能从 "+from+" 改为 "+to);
        String previous=from;for(String status:path){next.withArray("statusHistory").add(obj().put("from",previous).put("to",status).put("at",Instant.now().toString()));previous=status;}
    }
    private void settleFailedReservation(ObjectNode job){
        if(!reservationOpen(job)||job.path("submissionUncertain").asBoolean())return;
        boolean submitted=!text(job,"providerRequestId").isBlank()||!text(job,"providerTaskId").isBlank()||job.hasNonNull("providerAcceptedAt");
        if(submitted)settleReservation(job,true);else releaseReservation(job,"CONFIRMED_NOT_SUBMITTED");
    }
    private void settleReservation(ObjectNode job,boolean wasted){
        if(!reservationOpen(job))return;testBudget.settle(text(job,"type"),job.path("inputSnapshot"),wasted);
        mutate(id(job),current->current.put("testBudgetReservationStatus",wasted?"SETTLED_WASTE":"SETTLED_ACTUAL"));
    }
    private void releaseReservation(ObjectNode job,String reason){
        if(!reservationOpen(job))return;testBudget.release(text(job,"type"),job.path("inputSnapshot"));
        mutate(id(job),current->current.put("testBudgetReservationStatus","RELEASED").put("testBudgetReleaseReason",reason));
    }
    private boolean reservationOpen(ObjectNode job){return job.path("testBudgetReserved").asBoolean()&&"RESERVED".equals(text(job,"testBudgetReservationStatus"));}
    private void recordUnknownCost(ObjectNode job){recordCost(job,"UNKNOWN","POSSIBLY_BILLED",true);}
    private void recordTerminalCost(ObjectNode job){if(!Set.of("SUCCESS","FAILED","CANCELLED").contains(text(job,"status")))return;boolean known=job.path("costKnown").asBoolean(false);recordCost(job,text(job,"status"),job.path("submissionUncertain").asBoolean()?"POSSIBLY_BILLED":known?"ACTUAL":"ESTIMATED",false);}
    private void recordCost(ObjectNode job,String status,String billingStatus,boolean provisional){store.transaction(()->{List<ObjectNode> existing=store.list(COST_RECORD,project(job),null).stream().filter(record->id(job).equals(text(record,"generationJobId"))).toList();if(existing.stream().anyMatch(record->status.equals(text(record,"terminalStatus"))))return null;ObjectNode priceSnapshot=store.list(PRICE_SNAPSHOT,project(job),null).stream().max(Comparator.comparingInt(value->value.path("version").asInt())).orElseGet(()->store.create(PRICE_SNAPSHOT,obj().put("projectId",project(job)).put("version",1).put("currency","CNY").put("status","UNPRICED").put("source","LOCAL_CONFIG").set("prices",obj())));JsonNode usage=job.path("providerUsage");double amount=job.path("cost").asDouble(0);boolean known=job.path("costKnown").asBoolean(false),wasted=!provisional&&!"SUCCESS".equals(status);ObjectNode record=obj().put("projectId",project(job)).put("generationJobId",id(job)).put("taskId",id(job)).put("taskType",text(job,"type")).put("provider",job.path("provider").asText("UNKNOWN")).put("model",job.path("model").asText("UNKNOWN")).put("generationProfile",job.path("generationProfile").asText("TEST")).put("resolution",job.path("resolution").asText(job.path("inputSnapshot").path("resolution").asText(""))).put("inputTokens",usage.path("promptTokens").asLong(usage.path("inputTokens").asLong(0))).put("outputTokens",usage.path("completionTokens").asLong(usage.path("outputTokens").asLong(0))).put("totalTokens",usage.path("totalTokens").asLong(0)).put("estimatedCost",amount).put("currency",job.path("currency").asText("CNY")).put("billingStatus",billingStatus).put("provisional",provisional).put("selectedResult",false).put("wastedCost",wasted?amount:0).put("priceSnapshotId",id(priceSnapshot)).put("terminalStatus",status);if(known&&!provisional)record.put("actualCost",amount);if(!provisional)existing.stream().filter(old->old.path("provisional").asBoolean()).reduce((a,b)->b).ifPresent(old->record.put("supersedesCostRecordId",id(old)));if(job.hasNonNull("shotId"))record.put("shotId",text(job,"shotId"));JsonNode input=job.path("inputSnapshot");for(String field:List.of("episodeId","sceneId","episodeNo"))if(input.hasNonNull(field))record.set(field,input.path(field));for(String field:List.of("providerRequestId","providerTaskId"))if(job.hasNonNull(field))record.set(field,job.path(field));store.create(COST_RECORD,record);return null;});}
    private List<ObjectNode> latestCosts(String projectId){Map<String,ObjectNode> latest=new LinkedHashMap<>();for(ObjectNode cost:store.list(COST_RECORD,projectId,null)){String key=text(cost,"generationJobId");ObjectNode prior=latest.get(key);if(prior==null||(!cost.path("provisional").asBoolean()&&prior.path("provisional").asBoolean()))latest.put(key,cost);}return List.copyOf(latest.values());}
    private long elapsedMs(JsonNode job){String stamp=text(job,"startedAt");if(stamp.isBlank())stamp=text(job,"createdAt");if(stamp.isBlank())return 0;try{return Math.max(0,java.time.Duration.between(Instant.parse(stamp),Instant.now()).toMillis());}catch(RuntimeException ignored){return 0;}}
}
