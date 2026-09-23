package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.yourapp.drama.persistence.ResourceKind.NOVEL_AI_JOB;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NovelIntelligenceExecutorSafetyTest {
    @Test void uncertainTextSubmissionIsPersistedAndNeverAutomaticallyResubmitted(){
        DocumentStore store=mock(DocumentStore.class);NovelIntelligenceProvider provider=mock(NovelIntelligenceProvider.class);NovelIntelligenceRunService runs=mock(NovelIntelligenceRunService.class);NovelIntelligenceExecutor executor=new NovelIntelligenceExecutor(store,provider,runs);ObjectNode job=obj().put("id","job-1").put("projectId","project-1").put("revision",1);when(store.create(eq(NOVEL_AI_JOB),any())).thenReturn(job);when(store.update(eq(NOVEL_AI_JOB),eq("job-1"),eq(1L),any())).thenAnswer(call->call.getArgument(3));when(runs.reserve(anyString(),anyLong(),anyLong(),anyDouble())).thenReturn(NovelIntelligenceRunService.Reservation.none());when(provider.execute(any())).thenThrow(new ProviderException("NETWORK_ERROR","response lost","req-1",0,false,true));
        assertThatThrownBy(()->executor.execute("project-1",ir(),prompt())).isInstanceOf(ProviderException.class);ArgumentCaptor<ObjectNode> saved=ArgumentCaptor.forClass(ObjectNode.class);verify(store,atLeastOnce()).update(eq(NOVEL_AI_JOB),eq("job-1"),eq(1L),saved.capture());assertThat(saved.getAllValues()).anySatisfy(value->assertThat(value.path("status").asText()).isEqualTo("SUBMITTING"));ObjectNode terminal=saved.getAllValues().get(saved.getAllValues().size()-1);assertThat(terminal.path("status").asText()).isEqualTo("SUBMISSION_UNCERTAIN");assertThat(terminal.path("reconciliationRequired").asBoolean()).isTrue();assertThat(terminal.path("providerRequestId").asText()).isEqualTo("req-1");
        ObjectNode unresolved=terminal.put("compilerVersion","v1").put("contextHash","hash").put("taskType","CHUNK_ANALYSIS");when(store.list(NOVEL_AI_JOB,"project-1",null)).thenReturn(List.of(unresolved));when(provider.cacheIdentity("novel_analysis")).thenReturn("model");assertThatThrownBy(()->executor.executeOrReuse("project-1",ir(),prompt())).isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("NOVEL_INTELLIGENCE_RECONCILIATION_REQUIRED"));verify(provider,times(1)).execute(any());
    }
    @Test void budgetFailureIsPersistedBeforeAnyProviderSubmission(){
        DocumentStore store=mock(DocumentStore.class);NovelIntelligenceProvider provider=mock(NovelIntelligenceProvider.class);NovelIntelligenceRunService runs=mock(NovelIntelligenceRunService.class);NovelIntelligenceExecutor executor=new NovelIntelligenceExecutor(store,provider,runs);ObjectNode job=obj().put("id","job-2").put("projectId","project-1").put("revision",1);when(store.create(eq(NOVEL_AI_JOB),any())).thenReturn(job);when(store.update(eq(NOVEL_AI_JOB),eq("job-2"),eq(1L),any())).thenAnswer(call->call.getArgument(3));when(runs.reserve(anyString(),anyLong(),anyLong(),anyDouble())).thenThrow(new WorkflowException("NOVEL_INTELLIGENCE_BUDGET_EXCEEDED","budget"));
        assertThatThrownBy(()->executor.execute("project-1",ir(),prompt())).isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("NOVEL_INTELLIGENCE_BUDGET_EXCEEDED"));ArgumentCaptor<ObjectNode> saved=ArgumentCaptor.forClass(ObjectNode.class);verify(store).update(eq(NOVEL_AI_JOB),eq("job-2"),eq(1L),saved.capture());assertThat(saved.getValue().path("status").asText()).isEqualTo("FAILED");verifyNoInteractions(provider);
    }
    private static NovelPromptIR ir(){return new NovelPromptIR("novel-1",List.of(),List.of(),List.of(),List.of(),"STANDARD","",90,"hash","UNTRUSTED_NOVEL_TEXT",List.of(),"source");}
    private static NovelPromptCompiler.Compiled prompt(){return new NovelPromptCompiler.Compiled("CHUNK_ANALYSIS","novel_analysis","v1","system","user",Map.of("type","object"));}
}
