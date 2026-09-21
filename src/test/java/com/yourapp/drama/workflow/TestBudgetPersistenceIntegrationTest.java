package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TestBudgetPersistenceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void reservationsSurviveGuardRecreationAndStillBlockTheNextPaidRequest() {
        var environment = new MockEnvironment().withProperty("drama.provider.mode","live")
            .withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","1");
        var first = new TestBudgetGuard(environment,jdbc,mapper,transactionManager);
        var input = obj().put("testRun",true).put("testRunId","restart-safe-run").put("estimatedCost",.5);
        first.reserve("VIDEO",input);

        var afterRestart = new TestBudgetGuard(environment,jdbc,mapper,transactionManager);
        assertThat(afterRestart.snapshot("restart-safe-run").path("video").asInt()).isEqualTo(1);
        assertThat(afterRestart.snapshot("restart-safe-run").path("reservedCost").asDouble()).isEqualTo(.5);
        assertThatThrownBy(()->afterRestart.reserve("VIDEO",input))
            .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
    }
}
