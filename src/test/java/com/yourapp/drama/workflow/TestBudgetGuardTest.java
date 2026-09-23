package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test void pipelineAllowsOnlyFourKeyframeAndFourVideoReviewsAndBlocksTheNinthBeforeDispatch(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","volcengine")
                .withProperty("PIPELINE_TEST_MAX_COST_CNY","20")
                .withProperty("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8");
        var guard=new TestBudgetGuard(env);var input=obj().put("testRun",true).put("testRunId","vlm-hard-limit").put("testPhase","PIPELINE").put("estimatedCost",0);
        AtomicInteger providerCalls=new AtomicInteger();
        for(int i=0;i<4;i++){guard.reserve("KEYFRAME_QC",input);providerCalls.incrementAndGet();}
        assertThatThrownBy(()->guard.reserve("KEYFRAME_QC",input)).isInstanceOfSatisfying(WorkflowException.class,e->assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
        for(int i=0;i<4;i++){guard.reserve("VIDEO_QC",input);providerCalls.incrementAndGet();}
        assertThatThrownBy(()->guard.reserve("VIDEO_QC",input)).isInstanceOfSatisfying(WorkflowException.class,e->assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
        assertThat(providerCalls).hasValue(8);
        assertThat(guard.snapshot("vlm-hard-limit").path("keyframeQc").asInt()).isEqualTo(4);
        assertThat(guard.snapshot("vlm-hard-limit").path("videoQc").asInt()).isEqualTo(4);
    }

    @Test void phaseBLiveProfileEnforcesEveryExactLimitAndReservesConfiguredEstimates(){
        var env=new MockEnvironment().withProperty("drama.provider.mode","volcengine")
                .withProperty("PIPELINE_TEST_MAX_COST_CNY","35")
                .withProperty("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","8")
                .withProperty("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","11")
                .withProperty("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8")
                .withProperty("PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY","16")
                .withProperty("PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY","12")
                .withProperty("PIPELINE_TEST_LLM_ESTIMATED_COST_CNY","1")
                .withProperty("PIPELINE_TEST_VLM_ESTIMATED_COST_CNY","1")
                .withProperty("PIPELINE_TEST_TTS_ESTIMATED_COST_CNY","0.5");
        var guard=new TestBudgetGuard(env);var input=obj().put("testRun",true).put("testRunId","phase-b-exact").put("testPhase","PIPELINE");
        AtomicInteger calls=new AtomicInteger();
        reserve(guard,input,"STORY",11,calls);blocked(guard,input,"STORY",calls,11);
        reserve(guard,input,"KEYFRAME",8,calls);blocked(guard,input,"KEYFRAME",calls,19);
        reserve(guard,input,"VIDEO",4,calls);blocked(guard,input,"VIDEO",calls,23);
        reserve(guard,input,"TTS",2,calls);blocked(guard,input,"TTS",calls,25);
        for(int i=0;i<4;i++){guard.reserve("KEYFRAME_QC",input);calls.incrementAndGet();}
        for(int i=0;i<4;i++){guard.reserve("VIDEO_QC",input);calls.incrementAndGet();}
        blocked(guard,input,"VIDEO_QC",calls,33);
        assertThat(guard.snapshot("phase-b-exact").path("plannedCost").asDouble()).isGreaterThan(0);
    }

    private void reserve(TestBudgetGuard guard,ObjectNode input,String type,int count,AtomicInteger calls){for(int i=0;i<count;i++){guard.reserve(type,input);calls.incrementAndGet();}}
    private void blocked(TestBudgetGuard guard,ObjectNode input,String type,AtomicInteger calls,int expected){assertThatThrownBy(()->guard.reserve(type,input)).isInstanceOfSatisfying(WorkflowException.class,e->assertThat(e.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));assertThat(calls).hasValue(expected);}
}
