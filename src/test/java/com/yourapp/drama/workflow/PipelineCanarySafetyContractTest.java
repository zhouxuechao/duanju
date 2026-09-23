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
        assertThat(ready.path("plan").path("assetDependency").asText()).isEqualTo("A1");
        assertThat(ready.path("plan").path("requests").path("image").asInt()).isEqualTo(8);
        assertThat(ready.path("plan").path("requests").path("storyDirectorLlm").asInt()).isEqualTo(11);

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

    @Test void liveConfigurationGatesEveryPaidModelReviewerAndVoiceBeforeExecution(){
        Map<String,String> values=environment(true);
        for(String key:List.of("ARK_TEXT_MODEL","ARK_DIRECTOR_MODEL","ARK_VLM_MODEL","SEED_AUDIO_API_KEY","CANARY_TTS_VOICE_ID")){
            Map<String,String> missing=new HashMap<>(values);missing.remove(key);
            assertThat(LiveCanaryPlan.pipeline().validateLive(missing,List.of()).path("ready").asBoolean()).as(key).isFalse();
        }
        Map<String,String> fakeReviewer=new HashMap<>(values);fakeReviewer.put("VISUAL_REVIEWER","fake");
        assertThat(LiveCanaryPlan.pipeline().validateLive(fakeReviewer,List.of()).path("blocking").toString()).contains("REAL_VLM_REVIEWER");
        Map<String,String> mockVoice=new HashMap<>(values);mockVoice.put("CANARY_TTS_VOICE_ID","mock-voice");
        assertThat(LiveCanaryPlan.pipeline().validateLive(mockVoice,List.of()).path("blocking").toString()).contains("TTS_VOICE");
    }

    @Test void pipelineBudgetUsesIndependentLimitsAndFailsBeforeTheNinthImage(){
        MockEnvironment environment=new MockEnvironment()
                .withProperty("drama.provider.mode","volcengine")
                .withProperty("PIPELINE_TEST_MAX_COST_CNY","35")
                .withProperty("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","8")
                .withProperty("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4")
                .withProperty("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2")
                .withProperty("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","11")
                .withProperty("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8")
                .withProperty("PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY","16");
        TestBudgetGuard guard=new TestBudgetGuard(environment);
        ObjectNode input=obj().put("testRun",true).put("testRunId","pipeline-budget").put("testPhase","PIPELINE").put("estimatedCost",1);

        for(int i=0;i<8;i++)assertThat(guard.reserve("KEYFRAME",input)).isTrue();

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
        assertThat(runner).contains("ARK_TEXT_MODEL","ARK_DIRECTOR_MODEL","ARK_VLM_MODEL","VISUAL_REVIEWER","CANARY_TTS_VOICE_ID");
        assertThat(runner).contains("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS='8'","PIPELINE_TEST_MAX_REAL_LLM_REQUESTS='11'");
        assertThat(runner).contains("assetDependency='A1'");
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
        values.put("ARK_TEXT_MODEL","writer-model");
        values.put("ARK_DIRECTOR_MODEL","director-model");
        values.put("ARK_VLM_MODEL","vision-model");
        values.put("VISUAL_REVIEWER","volcengine");
        values.put("SEED_AUDIO_API_KEY","audio-test-placeholder");
        values.put("CANARY_TTS_VOICE_ID","canary-real-voice");
        values.put("CANARY_ASSET_DEPENDENCY","A1");
        values.put("PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","8");
        values.put("PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","4");
        values.put("PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","2");
        values.put("PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","11");
        values.put("PIPELINE_TEST_MAX_REAL_VLM_REQUESTS","8");
        values.put("PIPELINE_TEST_MAX_REAL_LIPSYNC_REQUESTS","0");
        values.put("PIPELINE_TEST_MAX_COST_CNY","35");
        values.put("PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY","16");
        values.put("PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY","12");
        values.put("PIPELINE_TEST_LLM_ESTIMATED_COST_CNY","1");
        values.put("PIPELINE_TEST_VLM_ESTIMATED_COST_CNY","1");
        values.put("PIPELINE_TEST_TTS_ESTIMATED_COST_CNY","0.5");
        if(runnerAuthorized)values.put("CANARY_PIPELINE_RUNNER_PREFLIGHT_OK","true");
        return values;
    }
}
