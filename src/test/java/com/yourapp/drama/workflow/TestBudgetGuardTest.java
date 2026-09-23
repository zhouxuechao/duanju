package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class TestBudgetGuardTest {
    @Test void liveCanaryCannotExceedVideoRequestOrMoneyLimit(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","live").withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","1").withProperty("TEST_MAX_COST_CNY","1");var guard=new TestBudgetGuard(env);
        var input=obj().put("testRun",true).put("testRunId","canary-1").put("estimatedCost",.4);guard.reserve("VIDEO",input);
        assertThatThrownBy(()->guard.reserve("VIDEO",input)).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
        assertThatThrownBy(()->guard.reserve("KEYFRAME",obj().put("testRun",true).put("testRunId","canary-money").put("estimatedCost",1.1))).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
    }
    @Test void lipsyncHasItsOwnLimitAndLocalTasksNeverConsumeAudioBudget(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","live").withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","1").withProperty("TEST_MAX_REAL_LIPSYNC_REQUESTS","1").withProperty("TEST_MAX_REAL_AUDIO_REQUESTS","1");var guard=new TestBudgetGuard(env);
        assertThatCode(()->{guard.reserve("VIDEO",obj().put("testRun",true).put("testRunId","categories"));guard.reserve("LIPSYNC",obj().put("testRun",true).put("testRunId","categories"));guard.reserve("RENDER",obj().put("testRun",true).put("testRunId","categories"));guard.reserve("TIMELINE",obj().put("testRun",true).put("testRunId","categories"));guard.reserve("TTS",obj().put("testRun",true).put("testRunId","categories"));}).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.reserve("LIPSYNC",obj().put("testRun",true).put("testRunId","categories"))).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
    }
    @Test void confirmedNotSubmittedReservationCanBeReleased(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","live").withProperty("TEST_MAX_REAL_IMAGE_REQUESTS","1");var guard=new TestBudgetGuard(env);var input=obj().put("testRun",true).put("testRunId","release");
        guard.reserve("KEYFRAME",input);guard.release("KEYFRAME",input);
        assertThatCode(()->guard.reserve("KEYFRAME",input)).doesNotThrowAnyException();
    }

    @Test void providerCanaryAllowsExactlyOneImageAndOneVideoAndRejectsInvalidCost(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","volcengine").withProperty("DRAMA_TEST_RUN","true")
                .withProperty("TEST_MAX_REAL_IMAGE_REQUESTS","1").withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","1").withProperty("TEST_MAX_COST_CNY","2");
        var guard=new TestBudgetGuard(env);var base=obj().put("testRun",true).put("testRunId","phase-a").put("estimatedCost",.5);
        guard.reserve("KEYFRAME",base);guard.reserve("VIDEO",base);
        assertThatThrownBy(()->guard.reserve("KEYFRAME",base)).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
        assertThatThrownBy(()->guard.reserve("VIDEO",base)).isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
        assertThatThrownBy(()->guard.reserve("KEYFRAME",obj().put("testRun",true).put("testRunId","negative").put("estimatedCost",-.1)))
                .isInstanceOfSatisfying(WorkflowException.class,e->org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("TEST_BUDGET_INVALID"));
    }
}
