package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCanaryPlanTest {
    @TempDir Path temporary;
    @Test void providerDryRunIsOneImageOneVideoAndNeverIncludesSecrets(){
        ObjectNode plan=LiveCanaryPlan.provider().toJson();
        assertThat(plan.path("phase").asText()).isEqualTo("PROVIDER");
        assertThat(plan.path("generationProfile").asText()).isEqualTo("TEST");
        assertThat(plan.path("requests").path("image").asInt()).isEqualTo(1);
        assertThat(plan.path("requests").path("video").asInt()).isEqualTo(1);
        assertThat(plan.path("requests").path("audio").asInt()).isZero();
        assertThat(plan.path("videoDurationSeconds").asInt()).isEqualTo(5);
        assertThat(plan.toString()).doesNotContain("apiKey", "ark-");
    }

    @Test void livePreflightRejectsWrongProfileModelsResolutionAndUnresolvedSubmissions(){
        LiveCanaryPlan plan=LiveCanaryPlan.provider();
        Map<String,String> correct=Map.of("RUN_LIVE_PROVIDER_CANARY","true","CANARY_GENERATION_PROFILE","TEST",
                "ARK_IMAGE_MODEL","doubao-seedream-5-0-260128","ARK_IMAGE_SIZE","2K",
                "ARK_VIDEO_MODEL","doubao-seedance-2-0-fast-260128","ARK_VIDEO_RESOLUTION","480p",
                "ARK_API_KEY","local-secret","TEST_MAX_REAL_IMAGE_REQUESTS","1","TEST_MAX_REAL_VIDEO_REQUESTS","1","TEST_MAX_COST_CNY","2");
        assertThat(plan.validateLive(correct,List.of()).path("ready").asBoolean()).isTrue();
        assertThat(plan.validateLive(with(correct,"CANARY_GENERATION_PROFILE","FINAL"),List.of()).path("ready").asBoolean()).isFalse();
        assertThat(plan.validateLive(with(correct,"ARK_VIDEO_RESOLUTION","720p"),List.of()).path("ready").asBoolean()).isFalse();
        assertThat(plan.validateLive(with(with(correct,"TEST_IMAGE_ESTIMATED_COST_CNY","1.1"),"TEST_VIDEO_ESTIMATED_COST_CNY","1.1"),List.of()).path("ready").asBoolean()).isFalse();
        assertThat(plan.validateLive(correct,List.of(obj().put("status","UNKNOWN").put("provider","VOLCENGINE"))).path("ready").asBoolean()).isFalse();
        assertThat(plan.validateLive(correct,List.of(obj().put("status","FAILED").put("submissionUncertain",true))).path("ready").asBoolean()).isFalse();
        assertThat(plan.validateLive(correct,List.of()).toString()).doesNotContain("local-secret");
    }

    @Test void pipelineLiveRequiresItsOwnFlagInAdditionToProviderFlag(){
        Map<String,String> onlyProvider=Map.of("RUN_LIVE_PROVIDER_CANARY","true");
        assertThatThrownBy(()->LiveCanaryPlan.pipeline().requireLiveFlags(onlyProvider)).hasMessageContaining("RUN_LIVE_PIPELINE_CANARY");
    }

    @Test void durableStateBlocksASecondPaidRunUntilThePreviousRunIsReconciled(){
        Path state=temporary.resolve("provider-canary-state.json");
        LiveCanaryState first=LiveCanaryState.start(state,"canary-1");
        first.advance("VIDEO_SUBMITTING","image-request-1","");
        assertThatThrownBy(()->LiveCanaryState.start(state,"canary-2"))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("CANARY_RECONCILIATION_REQUIRED"));
        assertThat(first.snapshot().path("status").asText()).isEqualTo("VIDEO_SUBMITTING");
        assertThat(first.snapshot().toString()).doesNotContain("https://","ark-");
    }

    private Map<String,String> with(Map<String,String> source,String key,String value){var copy=new java.util.HashMap<>(source);copy.put(key,value);return copy;}
}
