package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanarySafetyContractTest {
    @Test void pipelineRequiresItsOwnLiveFlagRunnerMarkerAndExactFailClosedBudgets(){
        ObjectNode ready=LiveCanaryPlan.pipeline().validateLive(environment(true),List.of());

        assertThat(ready.path("ready").asBoolean()).as(ready.path("blocking").toString()).isTrue();

        Map<String,String> missingMarker=environment(false);
        assertThat(LiveCanaryPlan.pipeline().validateLive(missingMarker,List.of()).path("blocking").toString()).contains("PIPELINE_RUNNER_PREFLIGHT");
        Map<String,String> missingBudget=environment(true);missingBudget.remove("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS");
        assertThat(LiveCanaryPlan.pipeline().validateLive(missingBudget,List.of()).path("ready").asBoolean()).isFalse();
        Map<String,String> negativeBudget=environment(true);negativeBudget.put("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","-1");
        assertThat(LiveCanaryPlan.pipeline().validateLive(negativeBudget,List.of()).path("ready").asBoolean()).isFalse();
    }

    @Test void directTestInvocationCannotBypassThePipelineRunner(){
        assertThatThrownBy(()->LiveCanaryPlan.pipeline().requireLiveFlags(environment(false)))
                .hasMessageContaining("CANARY_PIPELINE_RUNNER_PREFLIGHT_REQUIRED");
    }

    @Test void pipelineBudgetUsesIndependentLimitsAndFailsBeforeTheFifthImage(){
        MockEnvironment environment=new MockEnvironment()
                .withProperty("drama.provider.mode","volcengine")
                .withProperty("PIPELINE_TEST_MAX_COST_CNY","20")
                .withProperty("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8");
        TestBudgetGuard guard=new TestBudgetGuard(environment);
        ObjectNode input=obj().put("testRun",true).put("testRunId","pipeline-budget").put("testPhase","PIPELINE").put("estimatedCost",1);

        for(int i=0;i<4;i++)assertThat(guard.reserve("KEYFRAME",input)).isTrue();

        assertThatThrownBy(()->guard.reserve("KEYFRAME",input))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("TEST_BUDGET_EXCEEDED"));
    }

    @Test void runnerRequiresTwoIndependentConfirmationsAndSetsMarkerOnlyAfterPreflight() throws Exception {
        String runner=Files.readString(Path.of("scripts","run-pipeline-canary.ps1"));

        assertThat(runner).contains("if(-not $ConfirmLive)","RUN_LIVE_PIPELINE_CANARY","CANARY_PIPELINE_RUNNER_PREFLIGHT_OK");
        assertThat(runner).contains("RECONCILIATION_REQUIRED","SUCCEEDED","RESUMING PIPELINE CANARY");
        assertThat(runner).doesNotContain("$env:RUN_LIVE_PIPELINE_CANARY='true'");
        int tests=runner.indexOf("test.ps1");
        int capability=runner.indexOf("provider-capability-snapshot.json");
        int marker=runner.indexOf("$env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK='true'");
        int live=runner.indexOf("-Dtest=LivePipelineCanaryIT");
        assertThat(tests).isGreaterThanOrEqualTo(0);
        assertThat(marker).isGreaterThan(tests).isGreaterThan(capability);
        assertThat(live).isGreaterThan(marker);
        assertThat(runner.split("\\$env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK='true'",-1)).hasSize(2);
    }

    private Map<String,String> environment(boolean runnerAuthorized){
        Map<String,String> values=new HashMap<>();
        values.put("RUN_LIVE_PIPELINE_CANARY","true");
        values.put("CANARY_GENERATION_PROFILE","TEST");
        values.put("ARK_IMAGE_MODEL","doubao-seedream-5-0-260128");
        values.put("ARK_IMAGE_SIZE","2K");
        values.put("ARK_VIDEO_MODEL","doubao-seedance-2-0-fast-260128");
        values.put("ARK_VIDEO_RESOLUTION","480p");
        values.put("ARK_API_KEY","test-only-placeholder");
        values.put("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","4");
        values.put("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4");
        values.put("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2");
        values.put("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","2");
        values.put("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8");
        values.put("PIPELINE_TEST_MAX_COST_CNY","20");
        values.put("PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY","8");
        values.put("PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY","12");
        if(runnerAuthorized)values.put("CANARY_PIPELINE_RUNNER_PREFLIGHT_OK","true");
        return values;
    }
}
