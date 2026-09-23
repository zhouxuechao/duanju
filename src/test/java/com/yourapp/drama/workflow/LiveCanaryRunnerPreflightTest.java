package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCanaryRunnerPreflightTest {
    @Test void liveFlagAloneCannotAuthorizePaidCanary(){
        ObjectNode result=LiveCanaryPlan.provider().validateLive(correctEnvironment(false), List.of());

        assertThat(result.path("ready").asBoolean()).isFalse();
        assertThat(result.path("blocking")).anyMatch(item->"RUNNER_PREFLIGHT".equals(item.asText()));
    }

    @Test void runnerMarkerAndLiveFlagAuthorizeAnOtherwiseValidCanary(){
        ObjectNode result=LiveCanaryPlan.provider().validateLive(correctEnvironment(true),List.of());

        assertThat(result.path("ready").asBoolean()).as(result.path("blocking").toString()).isTrue();
    }

    @Test void directRequireWithoutRunnerMarkerFailsBeforeProviderWork(){
        assertThatThrownBy(()->LiveCanaryPlan.provider().requireLiveFlags(correctEnvironment(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANARY_RUNNER_PREFLIGHT_REQUIRED");
    }

    @Test void runnerSetsMarkerOnlyAfterNormalTestsAndNeverOnDryRunPath() throws Exception {
        String runner=Files.readString(Path.of("scripts","run-provider-canary.ps1"));
        int dryRunExit=runner.indexOf("DRY RUN ONLY");
        int normalTests=runner.indexOf("test.ps1");
        int authorization=runner.indexOf("$env:CANARY_RUNNER_PREFLIGHT_OK='true'");
        int liveTest=runner.indexOf("-Dtest=LiveProviderCanaryIT");

        assertThat(dryRunExit).isGreaterThanOrEqualTo(0);
        assertThat(authorization).isGreaterThan(normalTests).isGreaterThan(dryRunExit);
        assertThat(liveTest).isGreaterThan(authorization);
        assertThat(runner.substring(0,dryRunExit)).doesNotContain("CANARY_RUNNER_PREFLIGHT_OK='true'");
        assertThat(runner.split("\\$env:CANARY_RUNNER_PREFLIGHT_OK='true'",-1)).hasSize(2);
        assertThat(runner.lastIndexOf("Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK")).isGreaterThan(liveTest);
    }

    private Map<String,String> correctEnvironment(boolean runnerAuthorized){
        Map<String,String> values=new HashMap<>();
        values.put("RUN_LIVE_PROVIDER_CANARY","true");
        values.put("CANARY_GENERATION_PROFILE","TEST");
        values.put("ARK_IMAGE_MODEL","doubao-seedream-5-0-260128");
        values.put("ARK_IMAGE_SIZE","2K");
        values.put("ARK_VIDEO_MODEL","doubao-seedance-2-0-fast-260128");
        values.put("ARK_VIDEO_RESOLUTION","480p");
        values.put("ARK_API_KEY","test-only-placeholder");
        values.put("TEST_MAX_REAL_IMAGE_REQUESTS","1");
        values.put("TEST_MAX_REAL_VIDEO_REQUESTS","1");
        values.put("TEST_MAX_REAL_AUDIO_REQUESTS","0");
        values.put("TEST_MAX_REAL_LLM_REQUESTS","0");
        values.put("TEST_MAX_COST_CNY","2");
        values.put("TEST_IMAGE_ESTIMATED_COST_CNY","1");
        values.put("TEST_VIDEO_ESTIMATED_COST_CNY","1");
        if(runnerAuthorized)values.put("CANARY_RUNNER_PREFLIGHT_OK","true");
        return values;
    }
}
