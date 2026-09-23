package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;

import static com.yourapp.drama.workflow.Documents.obj;

/** Durable local lock for the standalone billable canary; a prior run must be reconciled before another starts. */
public final class LiveCanaryState {
    private static final Set<String> STATUSES=Set.of("READY","IMAGE_SUBMITTING","IMAGE_SUCCEEDED","VIDEO_SUBMITTING","VIDEO_SUBMITTED","RECONCILIATION_REQUIRED","FAILED","SUCCEEDED");
    private final Path path;private final ObjectMapper mapper=new ObjectMapper();private ObjectNode state;
    private LiveCanaryState(Path path,ObjectNode state){this.path=path;this.state=state;}

    public static LiveCanaryState start(Path path,String runId){
        if(runId==null||runId.isBlank())throw new IllegalArgumentException("runId is required");
        try{
            Files.createDirectories(path.toAbsolutePath().getParent());ObjectNode initial=obj().put("runId",safe(runId)).put("status","READY").put("createdAt",Instant.now().toString()).put("updatedAt",Instant.now().toString());
            Files.writeString(path,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(initial),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return new LiveCanaryState(path,initial);
        }catch(java.nio.file.FileAlreadyExistsException exists){throw new WorkflowException("CANARY_RECONCILIATION_REQUIRED","已有 Provider Canary 状态文件；请先核对上次请求并归档或清理状态文件，禁止重复提交");}
        catch(IOException error){throw new WorkflowException("CANARY_STATE_UNAVAILABLE","无法持久化 Provider Canary 状态，禁止提交付费请求");}
    }

    public synchronized void advance(String status,String providerRequestId,String providerTaskId){
        if(!STATUSES.contains(status))throw new IllegalArgumentException("Unknown canary status: "+status);
        ObjectNode next=state.deepCopy().put("status",status).put("updatedAt",Instant.now().toString());
        if(providerRequestId!=null&&!providerRequestId.isBlank())next.put("providerRequestId",safe(providerRequestId));
        if(providerTaskId!=null&&!providerTaskId.isBlank())next.put("providerTaskId",safe(providerTaskId));
        write(next);state=next;
    }

    public synchronized ObjectNode snapshot(){return state.deepCopy();}

    private void write(ObjectNode value){Path temporary=path.resolveSibling(path.getFileName()+".tmp");try{Files.writeString(temporary,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException error){throw new WorkflowException("CANARY_STATE_UNAVAILABLE","无法更新 Provider Canary 状态；停止后续付费请求");}}
    private static String safe(String value){String clean=value.replaceAll("[\\r\\n\\t]"," ");if(clean.contains("://")||clean.matches(".*ark-[A-Za-z0-9-]{10,}.*"))throw new IllegalArgumentException("Canary state must not contain credentials or URLs");return clean.substring(0,Math.min(clean.length(),200));}
}
