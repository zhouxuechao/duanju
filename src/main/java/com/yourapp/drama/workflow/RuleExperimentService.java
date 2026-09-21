package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import static com.yourapp.drama.persistence.ResourceKind.RULE_EXPERIMENT;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class RuleExperimentService {
    private final DocumentStore store;
    private final ObjectMapper mapper;
    public RuleExperimentService(DocumentStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}

    public ObjectNode create(String projectId,ObjectNode request){
        store.get(com.yourapp.drama.persistence.ResourceKind.PROJECT,projectId);
        requireObject(request,"frozenInputSnapshot"); requireObject(request,"variantA"); requireObject(request,"variantB");
        String phase=required(request,"targetPhase");
        ObjectNode experiment=obj().put("projectId",projectId).put("targetPhase",phase).put("status","DRAFT")
            .put("frozenInputSnapshotHash",hash(request.path("frozenInputSnapshot"))).put("createdAtClient",Instant.now().toString());
        experiment.set("frozenInputSnapshot",request.path("frozenInputSnapshot").deepCopy());
        experiment.set("variantA",request.path("variantA").deepCopy()); experiment.set("variantB",request.path("variantB").deepCopy());
        experiment.set("outputs",obj()); experiment.set("metrics",obj());
        return store.create(RULE_EXPERIMENT,experiment);
    }

    public ObjectNode record(String experimentId,ObjectNode request){
        return store.transaction(()->{
            ObjectNode current=store.getForUpdate(RULE_EXPERIMENT,experimentId),next=current.deepCopy();
            if(request.path("outputs").isObject())next.set("outputs",request.path("outputs").deepCopy());
            if(request.path("metrics").isObject())next.set("metrics",request.path("metrics").deepCopy());
            String preference=text(request,"humanPreference");
            if(!preference.isBlank()&&!Set.of("A","B","TIE").contains(preference))throw new IllegalArgumentException("humanPreference 只能是 A、B 或 TIE");
            if(!preference.isBlank())next.put("humanPreference",preference);
            if(request.has("note"))next.put("note",text(request,"note"));
            next.put("status",preference.isBlank()?"COMPLETED":"REVIEWED").put("completedAt",Instant.now().toString());
            return store.update(RULE_EXPERIMENT,experimentId,revision(current),next);
        });
    }

    private void requireObject(JsonNode request,String field){if(!request.path(field).isObject()||request.path(field).isEmpty())throw new IllegalArgumentException(field+" 必须是非空对象");}
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException("无法冻结实验输入",e);}}
}
