package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import static com.yourapp.drama.persistence.ResourceKind.NOVEL_AI_JOB;
import static com.yourapp.drama.workflow.Documents.*;

/** Executes one bounded request and durably records its exact model/compiler/source provenance. */
@Service
public class NovelIntelligenceExecutor {
    private final DocumentStore store;private final NovelIntelligenceProvider provider;
    public NovelIntelligenceExecutor(DocumentStore store,NovelIntelligenceProvider provider){this.store=store;this.provider=provider;}
    public String cacheIdentity(String modelRole){return provider.cacheIdentity(modelRole);}
    public NovelIntelligenceProvider.Result execute(String projectId,NovelPromptIR ir,NovelPromptCompiler.Compiled prompt){
        ObjectNode sourceIds=obj();sourceIds.set("chapterIds",array(ir.chapterIds()));sourceIds.set("chunkIds",array(ir.chunkIds()));sourceIds.set("entityIds",array(ir.entityIds()));sourceIds.set("factIds",array(ir.factIds()));
        ObjectNode job=store.create(NOVEL_AI_JOB,obj().put("projectId",projectId).put("novelId",ir.novelId()).put("taskType",prompt.taskType()).put("status","RUNNING").put("profile",ir.profile()).put("contextHash",ir.contextHash()).put("compilerVersion",prompt.compilerVersion()).put("sourceBoundary",ir.sourceBoundary()).put("startedAt",Instant.now().toString()).set("sourceIds",sourceIds));
        try{
            NovelIntelligenceProvider.Result result=provider.execute(prompt);ObjectNode done=job.deepCopy().put("status","SUCCEEDED").put("provider",result.provider()).put("model",result.model()).put("providerRequestId",result.requestId()).put("finishReason",result.finishReason()).put("inputTokens",result.inputTokens()).put("outputTokens",result.outputTokens()).put("simulated",result.simulated()).put("completedAt",Instant.now().toString());
            store.update(NOVEL_AI_JOB,id(job),revision(job),done);return result;
        }catch(RuntimeException error){
            ObjectNode failed=job.deepCopy().put("status","FAILED").put("failureCode",error instanceof com.yourapp.drama.model.ProviderException providerError?providerError.code():"NOVEL_AI_FAILED").put("failureReason",String.valueOf(error.getMessage())).put("completedAt",Instant.now().toString());
            store.update(NOVEL_AI_JOB,id(job),revision(job),failed);throw error;
        }
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode array(java.util.List<String> values){var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();values.forEach(result::add);return result;}
}
