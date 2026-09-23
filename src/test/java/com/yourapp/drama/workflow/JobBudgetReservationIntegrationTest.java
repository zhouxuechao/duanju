package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.persistence.ResourceKind.GENERATION_JOB;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

@SpringBootTest @ActiveProfiles("test") @Transactional
class JobBudgetReservationIntegrationTest {
    @Autowired DocumentStore store;@Autowired JobService jobs;@MockitoBean TestBudgetGuard budget;

    @Test void idempotentEnqueueLooksUpExistingJobBeforeReservingBudget(){
        ObjectNode project=store.create(PROJECT,obj().put("name","预算幂等").put("idea","同一请求只预留一次"));ObjectNode input=obj().put("testRun",true).put("testRunId","budget-idempotent");
        ObjectNode first=jobs.enqueue(id(project),null,"STORY",input,"same-request");ObjectNode second=jobs.enqueue(id(project),null,"STORY",input,"same-request");
        assertThat(id(second)).isEqualTo(id(first));verify(budget,times(1)).reserve(eq("STORY"),argThat(snapshot->
            "budget-idempotent".equals(snapshot.path("testRunId").asText())&&"TEST".equals(snapshot.path("generationProfile").asText())));
    }
    @Test void sameRequestKeyWithDifferentInputCannotSilentlyReturnAnOldPaidJob(){
        ObjectNode project=store.create(PROJECT,obj().put("name","请求内容校验").put("idea","两次输入不一样"));
        jobs.enqueue(id(project),null,"STORY",obj().put("idea","first"),"same-key");
        assertThatThrownBy(()->jobs.enqueue(id(project),null,"STORY",obj().put("idea","second"),"same-key"))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("请求标识");
        assertThat(store.list(GENERATION_JOB,id(project),null)).hasSize(1);
    }
    @Test void taskCenterFieldsExistFromQueueThroughTerminalState(){
        ObjectNode project=store.create(PROJECT,obj().put("name","任务中心").put("idea","任务字段完整"));
        ObjectNode queued=jobs.enqueue(id(project),null,"STORY",obj(),"task-center-fields");
        assertThat(text(queued,"localTaskId")).isEqualTo(id(queued));
        assertThat(text(queued,"provider")).isEqualTo("MOCK");
        assertThat(text(queued,"model")).isEqualTo("mock-writer");
        assertThat(text(queued,"phase")).isEqualTo("QUEUED");
        assertThat(queued.path("retryCount").asInt()).isZero();
        ObjectNode running=jobs.claim(id(project)).orElseThrow();
        assertThat(text(running,"phase")).isEqualTo("EXECUTING");
        assertThat(text(running,"startedAt")).isNotBlank();
        ObjectNode failed=jobs.fail(id(running),"TEST_FAILURE","用于验证任务字段",false,false);
        assertThat(text(failed,"phase")).isEqualTo("FAILED");
        assertThat(failed.path("elapsedMs").isIntegralNumber()).isTrue();
    }
}
