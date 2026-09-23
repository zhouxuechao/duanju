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

import static com.yourapp.drama.workflow.Documents.obj;

/** Private runtime vault for opaque provider URLs needed after a process restart; never use it as evidence or UI state. */
public final class PipelineCanaryArtifactVault {
    private final Path path;private final ObjectMapper mapper;private ObjectNode document;
    private PipelineCanaryArtifactVault(Path path,ObjectMapper mapper,ObjectNode document){this.path=path;this.mapper=mapper;this.document=document;}

    public static PipelineCanaryArtifactVault start(Path path,String runId){
        if(Files.exists(path))throw new WorkflowException("PIPELINE_CANARY_VAULT_EXISTS","Pipeline Canary runtime vault already exists");
        ObjectMapper mapper=new ObjectMapper();ObjectNode value=obj().put("runId",safeKey(runId)).put("createdAt",Instant.now().toString());value.putObject("values");
        PipelineCanaryArtifactVault vault=new PipelineCanaryArtifactVault(path,mapper,value);vault.write();return vault;
    }
    public static PipelineCanaryArtifactVault open(Path path){
        try{ObjectMapper mapper=new ObjectMapper();ObjectNode value=(ObjectNode)mapper.readTree(Files.readString(path,StandardCharsets.UTF_8));if(value.path("runId").asText().isBlank()||!value.path("values").isObject())throw new IllegalArgumentException("Pipeline Canary runtime vault is invalid");return new PipelineCanaryArtifactVault(path,mapper,value);}
        catch(IOException error){throw new WorkflowException("PIPELINE_CANARY_VAULT_UNAVAILABLE","Pipeline Canary runtime vault cannot be read");}
    }
    public synchronized void put(String key,String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Pipeline Canary runtime value is required");document.withObject("/values").put(safeKey(key),value);write();}
    public synchronized String require(String key){String value=document.path("values").path(safeKey(key)).asText();if(value.isBlank())throw new WorkflowException("PIPELINE_CANARY_ARTIFACT_MISSING","Pipeline Canary runtime artifact is missing: "+key);return value;}
    public synchronized boolean contains(String key){return !document.path("values").path(safeKey(key)).asText().isBlank();}
    public String runId(){return document.path("runId").asText();}
    private void write(){try{Path parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}catch(IOException error){throw new WorkflowException("PIPELINE_CANARY_VAULT_UNAVAILABLE","Pipeline Canary runtime vault cannot be persisted");}}
    private static String safeKey(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,239}"))throw new IllegalArgumentException("Pipeline Canary runtime key is invalid");return value;}
}
