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
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest @ActiveProfiles("test") @Transactional
class JobBudgetReservationIntegrationTest {
    @Autowired DocumentStore store;@Autowired JobService jobs;@MockitoBean TestBudgetGuard budget;

    @Test void idempotentEnqueueLooksUpExistingJobBeforeReservingBudget(){
        ObjectNode project=store.create(PROJECT,obj().put("name","预算幂等").put("idea","同一请求只预留一次"));ObjectNode input=obj().put("testRun",true).put("testRunId","budget-idempotent");
        ObjectNode first=jobs.enqueue(id(project),null,"STORY",input,"same-request");ObjectNode second=jobs.enqueue(id(project),null,"STORY",input,"same-request");
        assertThat(id(second)).isEqualTo(id(first));verify(budget,times(1)).reserve("STORY",input);
    }
}
