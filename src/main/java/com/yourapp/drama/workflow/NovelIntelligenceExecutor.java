package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Set;
import static com.yourapp.drama.persistence.ResourceKind.NOVEL_AI_JOB;
import static com.yourapp.drama.workflow.Documents.*;

/** Executes one bounded request and durably records its exact model/compiler/source provenance. */
@Service
public class NovelIntelligenceExecutor {
    private final DocumentStore store;private final NovelIntelligenceProvider provider;private final NovelIntelligenceRunService runs;
    public NovelIntelligenceExecutor(DocumentStore store,NovelIntelligenceProvider provider,NovelIntelligenceRunService runs){this.store=store;this.provider=provider;this.runs=runs;}
    public String cacheIdentity(String modelRole){return provider.cacheIdentity(modelRole);}
    public NovelIntelligenceProvider.Result execute(String projectId,NovelPromptIR ir,NovelPromptCompiler.Compiled prompt){
        ObjectNode sourceIds=obj();sourceIds.set("chapterIds",array(ir.chapterIds()));sourceIds.set("chunkIds",array(ir.chunkIds()));sourceIds.set("entityIds",array(ir.entityIds()));sourceIds.set("factIds",array(ir.factIds()));
        ObjectNode job=store.create(NOVEL_AI_JOB,obj().put("projectId",projectId).put("novelId",ir.novelId()).put("taskType",prompt.taskType()).put("status","PENDING").put("profile",ir.profile()).put("contextHash",ir.contextHash()).put("compilerVersion",prompt.compilerVersion()).put("sourceBoundary",ir.sourceBoundary()).put("startedAt",Instant.now().toString()).set("sourceIds",sourceIds));
        long estimatedInput=Math.max(1,(prompt.systemPrompt().length()+prompt.userPrompt().length()+1)/2),estimatedOutput=switch(prompt.taskType()){case "CHUNK_ANALYSIS","CHAPTER_SYNTHESIS"->2048;default->4096;};NovelIntelligenceRunService.Reservation reservation;
        try{reservation=runs.reserve(prompt.taskType(),estimatedInput,estimatedOutput,0);}catch(RuntimeException error){ObjectNode failed=job.deepCopy().put("status","FAILED").put("failureCode",error instanceof WorkflowException workflow?workflow.code():"NOVEL_AI_BUDGET_FAILED").put("failureReason",String.valueOf(error.getMessage())).put("completedAt",Instant.now().toString());store.update(NOVEL_AI_JOB,id(job),revision(job),failed);throw error;}
        job=store.update(NOVEL_AI_JOB,id(job),revision(job),job.deepCopy().put("status","SUBMITTING").put("submissionStartedAt",Instant.now().toString()));
        try{
            NovelIntelligenceProvider.Result result=provider.execute(prompt);ObjectNode done=job.deepCopy().put("status","SUCCEEDED").put("provider",result.provider()).put("model",result.model()).put("providerRequestId",result.requestId()).put("finishReason",result.finishReason()).put("inputTokens",result.inputTokens()).put("outputTokens",result.outputTokens()).put("simulated",result.simulated()).put("completedAt",Instant.now().toString());done.set("output",result.value().deepCopy());
            store.update(NOVEL_AI_JOB,id(job),revision(job),done);runs.provider(result.model(),result.requestId());runs.complete(reservation,result.inputTokens()<0?estimatedInput:result.inputTokens(),result.outputTokens()<0?estimatedOutput:result.outputTokens());return result;
        }catch(RuntimeException error){
            runs.fail(reservation);boolean uncertain=error instanceof com.yourapp.drama.model.ProviderException providerError&&providerError.uncertain();ObjectNode failed=job.deepCopy().put("status",uncertain?"SUBMISSION_UNCERTAIN":"FAILED").put("failureCode",error instanceof com.yourapp.drama.model.ProviderException providerError?providerError.code():"NOVEL_AI_FAILED").put("failureReason",String.valueOf(error.getMessage())).put("submissionUncertain",uncertain).put("reconciliationRequired",uncertain).put("completedAt",Instant.now().toString());if(error instanceof com.yourapp.drama.model.ProviderException providerError&&providerError.requestId()!=null)failed.put("providerRequestId",providerError.requestId());
            store.update(NOVEL_AI_JOB,id(job),revision(job),failed);throw error;
        }
    }
    public NovelIntelligenceProvider.Result executeOrReuse(String projectId,NovelPromptIR ir,NovelPromptCompiler.Compiled prompt){
        String identity=cacheIdentity(prompt.modelRole());
        ObjectNode unresolved=store.list(NOVEL_AI_JOB,projectId,null).stream().filter(job->prompt.taskType().equals(text(job,"taskType"))&&prompt.compilerVersion().equals(text(job,"compilerVersion"))&&ir.contextHash().equals(text(job,"contextHash"))&&(job.path("submissionUncertain").asBoolean()||Set.of("SUBMITTING","SUBMISSION_UNCERTAIN").contains(text(job,"status"))||("RUNNING".equals(text(job,"status"))&&!text(job,"providerRequestId").isBlank()))).findFirst().orElse(null);if(unresolved!=null)throw new WorkflowException("NOVEL_INTELLIGENCE_RECONCILIATION_REQUIRED","已有状态不确定的文本请求 "+text(unresolved,"providerRequestId")+"，禁止重复提交");
        ObjectNode cached=store.list(NOVEL_AI_JOB,projectId,null).stream().filter(job->prompt.taskType().equals(text(job,"taskType"))&&prompt.compilerVersion().equals(text(job,"compilerVersion"))&&ir.contextHash().equals(text(job,"contextHash"))&&"SUCCEEDED".equals(text(job,"status"))&&identity.equals(text(job,"model"))&&job.path("output").isObject()).findFirst().orElse(null);
        if(cached!=null)return new NovelIntelligenceProvider.Result((ObjectNode)cached.path("output").deepCopy(),text(cached,"provider"),text(cached,"model"),text(cached,"providerRequestId"),text(cached,"finishReason"),cached.path("inputTokens").asLong(-1),cached.path("outputTokens").asLong(-1),cached.path("simulated").asBoolean());
        return execute(projectId,ir,prompt);
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode array(java.util.List<String> values){var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();values.forEach(result::add);return result;}
}
