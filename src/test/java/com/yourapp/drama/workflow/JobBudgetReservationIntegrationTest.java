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

import java.util.Set;

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

    @Test void pipelineCanaryPaidJobsAreSingleAttemptWhileNormalJobsKeepTheirRetryPolicy(){
        ObjectNode canary=store.create(PROJECT,obj().put("name","Pipeline Canary").put("idea","付费请求只提交一次")
            .put("testRun",true).put("testRunId","single-attempt").put("testPhase","PIPELINE"));
        Set<String> paid=Set.of("STORY","SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL","ASSET_IMAGE","STORYBOARD","KEYFRAME","KEYFRAME_QC","VIDEO_QC","VIDEO","TTS","LIPSYNC");
        for(String type:paid)assertThat(jobs.enqueue(id(canary),null,type,obj(),"single-"+type).path("maxAttempts").asInt()).as(type).isEqualTo(1);

        ObjectNode normal=store.create(PROJECT,obj().put("name","普通项目").put("idea","保留原重试策略"));
        assertThat(jobs.enqueue(id(normal),null,"VIDEO",obj(),"normal-video").path("maxAttempts").asInt()).isEqualTo(3);
    }

    @Test void pipelineCanaryRetryableFailureNeverWaitsForCreationRetryAndUncertainFailureRequiresReconciliation(){
        ObjectNode project=store.create(PROJECT,obj().put("name","Pipeline Canary").put("idea","失败关闭")
            .put("testRun",true).put("testRunId","fail-closed").put("testPhase","PIPELINE"));
        ObjectNode retryable=jobs.enqueue(id(project),null,"VIDEO",obj(),"retryable-video");
        ObjectNode running=jobs.claim(id(project)).orElseThrow();
        ObjectNode failed=jobs.fail(id(running),"HTTP_503","明确未提交",true,false);
        assertThat(text(failed,"status")).isEqualTo("FAILED");
        assertThat(text(failed,"status")).isNotEqualTo("RETRY_WAIT");

        ObjectNode uncertain=jobs.enqueue(id(project),null,"KEYFRAME",obj(),"uncertain-image");
        while(!id(uncertain).equals(id(running=jobs.claim(id(project)).orElseThrow()))) jobs.fail(id(running),"TEST_DRAIN","测试清理",false,false);
        ObjectNode unknown=jobs.fail(id(running),"REQUEST_TIMEOUT","响应丢失",true,true);
        assertThat(text(unknown,"status")).isEqualTo("UNKNOWN");
        assertThat(unknown.path("reconciliationRequired").asBoolean()).isTrue();
        assertThat(jobs.claim(id(project))).isEmpty();
    }
}
