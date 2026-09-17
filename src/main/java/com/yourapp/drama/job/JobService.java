package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.JobType;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.workflow.WorkflowException;
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
    public JobService(DocumentStore store,JobEvents events){this.store=store;this.events=events;}
    public ObjectNode enqueue(String projectId,String shotId,String type,JsonNode input,String requestKey){
        try{JobType.valueOf(type);}catch(IllegalArgumentException error){throw new WorkflowException("JOB_TYPE_INVALID","当前流程不支持任务类型："+type);}
        ObjectNode job=store.transaction(()->{
            store.getForUpdate(PROJECT,projectId);
            if(requestKey!=null&&!requestKey.isBlank())for(ObjectNode old:store.list(GENERATION_JOB,projectId,null))if(requestKey.equals(text(old,"requestKey"))){
                if(!type.equals(text(old,"type"))||!Objects.equals(shotId,old.hasNonNull("shotId")?text(old,"shotId"):null))throw new WorkflowException("IDEMPOTENCY_CONFLICT","此请求标识已用于其他操作");
                return old;
            }
            ObjectNode next=obj().put("projectId",projectId).put("type",type).put("status","QUEUED").put("progress",0)
                .put("attempts",0).put("maxAttempts",3).put("cost",0).put("costKnown",false).put("requestKey",requestKey==null?UUID.randomUUID().toString():requestKey);
            if(shotId!=null)next.put("shotId",shotId);
            next.set("inputSnapshot",input.deepCopy()); next.set("outputSnapshot",obj());
            next.putArray("statusHistory").add(obj().put("to","QUEUED").put("at",java.time.Instant.now().toString()));
            return store.create(GENERATION_JOB,next);
        }); events.publish(job);return job;
    }
    public ObjectNode mutate(String id,Consumer<ObjectNode> changes){
        ObjectNode saved=store.transaction(()->{ObjectNode old=store.getForUpdate(GENERATION_JOB,id);ObjectNode next=old.deepCopy();changes.accept(next);recordTransition(old,next);return store.update(GENERATION_JOB,id,revision(old),next);});
        events.publish(saved);return saved;
    }
    public Optional<ObjectNode> claim(){
        for(ObjectNode candidate:store.list(GENERATION_JOB,null,null)){
            String status=text(candidate,"status");
            if(!status.equals("QUEUED")&&!status.equals("RETRY_WAIT"))continue;
            if(candidate.hasNonNull("retryAt")&&Instant.parse(text(candidate,"retryAt")).isAfter(Instant.now()))continue;
            ObjectNode result=store.transaction(()->{
                ObjectNode current=store.getForUpdate(GENERATION_JOB,id(candidate));
                if(!Set.of("QUEUED","RETRY_WAIT").contains(text(current,"status")))return null;
                ObjectNode next=current.deepCopy().put("status","RUNNING").put("startedAt",Instant.now().toString()).put("attempts",current.path("attempts").asInt()+1).put("progress",5);
                recordTransition(current,next);
                next.remove("failureReason");return store.update(GENERATION_JOB,id(current),revision(current),next);
            });
            if(result!=null){events.publish(result);return Optional.of(result);}
        }
        return Optional.empty();
    }
    public ObjectNode succeed(String id,JsonNode output){return mutate(id,j->{
        if(text(j,"status").equals("CANCELLED"))return;
        j.put("status","SUCCESS").put("progress",100).put("completedAt",Instant.now().toString());j.set("outputSnapshot",output.deepCopy());
    });}
    public ObjectNode fail(String id,String code,String reason,boolean retryable,boolean uncertain){return mutate(id,j->{
        if(Set.of("CANCELLED","SUCCESS").contains(text(j,"status")))return;
        boolean unresolved=uncertain||j.path("submissionUncertain").asBoolean();
        boolean retry=retryable&&!unresolved&&!j.path("cancelRequested").asBoolean()&&j.path("attempts").asInt()<j.path("maxAttempts").asInt(3);
        j.put("status",retry?"RETRY_WAIT":"FAILED").put("failureCode",code).put("failureReason",reason)
            .put("submissionUncertain",unresolved).put("retryable",retryable&&!unresolved);
        if(retry)j.put("retryAt",Instant.now().plusSeconds(5L*(1L<<Math.min(j.path("attempts").asInt(),5))).toString());
    });}
    public ObjectNode retry(String id){return store.transaction(()->{
        ObjectNode j=store.getForUpdate(GENERATION_JOB,id);
        if(!Set.of("FAILED","CANCELLED").contains(text(j,"status")))throw new WorkflowException("NOT_RETRYABLE","仅失败或取消的任务可以重试");
        if(j.path("submissionUncertain").asBoolean())throw new WorkflowException("SUBMISSION_UNCERTAIN","服务商可能已接受请求，请先在控制台核对任务，避免重复扣费");
        for(ObjectNode existing:store.list(GENERATION_JOB,project(j),null))if(id.equals(text(existing.path("inputSnapshot"),"retryOfJobId"))&&text(j,"type").equals(text(existing,"type"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCESS").contains(text(existing,"status")))return existing;
        if(!text(j,"providerRequestId").isBlank()&&!"SHOT_DETAIL".equals(text(j,"type")))throw new WorkflowException("NEW_TAKE_REQUIRED","服务商已创建任务，请使用重拍生成新版本");
        if(j.hasNonNull("shotId"))for(ObjectNode other:store.list(GENERATION_JOB,project(j),null))
            if(text(j,"shotId").equals(text(other,"shotId"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT").contains(text(other,"status"))&&!"ARCHIVE".equals(text(other,"type")))throw new WorkflowException("GENERATION_ACTIVE","此镜头已有生产任务，请等待完成");
        ObjectNode input=(ObjectNode)j.path("inputSnapshot").deepCopy();input.remove("reuseProviderOutput");input.put("retryOfJobId",id);
        return enqueue(project(j),j.hasNonNull("shotId")?text(j,"shotId"):null,text(j,"type"),input,"retry:"+id+":"+UUID.randomUUID());
    });}
    public ObjectNode revalidate(String id){return store.transaction(()->{
        ObjectNode j=store.getForUpdate(GENERATION_JOB,id);
        if(!Set.of("FAILED","CANCELLED").contains(text(j,"status")))throw new WorkflowException("NOT_REVALIDATABLE","仅失败或取消的任务可以本地复核");
        if(!j.path("providerOutput").isObject()||j.path("providerOutput").isEmpty())throw new WorkflowException("NO_VALIDATED_PROVIDER_OUTPUT","任务没有可本地复核的完整结构化输出");
        for(ObjectNode existing:store.list(GENERATION_JOB,project(j),null))if(id.equals(text(existing.path("inputSnapshot"),"revalidationOfJobId"))&&text(j,"type").equals(text(existing,"type"))&&Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCESS").contains(text(existing,"status")))return existing;
        ObjectNode input=(ObjectNode)j.path("inputSnapshot").deepCopy();input.put("retryOfJobId",id).put("revalidationOfJobId",id).put("reuseProviderOutput",true);
        return enqueue(project(j),j.hasNonNull("shotId")?text(j,"shotId"):null,text(j,"type"),input,"revalidate:"+id+":"+UUID.randomUUID());
    });}
    public ObjectNode cancel(String id){return mutate(id,j->{
        if(Set.of("SUCCESS","FAILED","CANCELLED").contains(text(j,"status")))return;
        j.put("cancelRequested",true);
        if(!text(j,"status").equals("RUNNING"))j.put("status","CANCELLED");
    });}
    private void recordTransition(ObjectNode old,ObjectNode next){
        String from=text(old,"status"),to=text(next,"status");if(from.equals(to))return;
        List<String> path;
        if(from.equals("RUNNING")&&to.equals("RETRY_WAIT"))path=List.of("FAILED","RETRY_WAIT");
        else if(from.equals("RETRY_WAIT")&&to.equals("RUNNING"))path=List.of("QUEUED","RUNNING");
        else if((from.equals("QUEUED")&&Set.of("RUNNING","CANCELLED").contains(to)) ||
                (from.equals("RUNNING")&&Set.of("SUCCESS","FAILED","CANCELLED").contains(to)) ||
                (from.equals("RETRY_WAIT")&&to.equals("CANCELLED")))path=List.of(to);
        else throw new WorkflowException("ILLEGAL_JOB_TRANSITION","任务状态不能从 "+from+" 改为 "+to);
        String previous=from;for(String status:path){next.withArray("statusHistory").add(obj().put("from",previous).put("to",status).put("at",Instant.now().toString()));previous=status;}
    }
}
