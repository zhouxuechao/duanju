package com.yourapp.drama.provider;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LivePipelineCanaryRequestContractTest {
    @Test void livePipelineIsDisabledByDefaultResumableAndDelegatesToTheProductionWorkflow() throws Exception {
        String source=Files.readString(Path.of("src","test","java","com","yourapp","drama","provider","LivePipelineCanaryIT.java"));

        assertThat(source).contains(
                "@EnabledIfEnvironmentVariable(named=\"RUN_LIVE_PIPELINE_CANARY\"",
                "LiveCanaryPlan.pipeline().requireLiveFlags(System.getenv())",
                "new PipelineCanaryCapabilityGate",
                "new PipelineCanaryProductionGate",
                "pipeline-canary-state.json",
                "PipelineCanaryState.open",
                "production.start",
                "production.reconcile",
                "production.resume",
                "state.productionSnapshot");
        assertThat(source).doesNotContain("new VolcengineImageGenerator","new SeedAudioVoiceGenerator","new PipelineCanaryEngine","ffmpeg","video.submit","keyframePrompt(","videoPrompt(");
        assertThat(source).doesNotContain("RUN_LIVE_PIPELINE_CANARY\",matches=\"(?i)false\"");
    }
}
