package com.yourapp.drama.provider;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LivePipelineCanaryRequestContractTest {
    @Test void livePipelineIsDisabledByDefaultResumableAndUsesTheDirectFirstFrameRoute() throws Exception {
        String source=Files.readString(Path.of("src","test","java","com","yourapp","drama","provider","LivePipelineCanaryIT.java"));

        assertThat(source).contains(
                "@EnabledIfEnvironmentVariable(named=\"RUN_LIVE_PIPELINE_CANARY\"",
                "LiveCanaryPlan.pipeline().requireLiveFlags(System.getenv())",
                "new PipelineCanaryCapabilityGate",
                "pipeline-canary-state.json",
                "PipelineCanaryState.open",
                "PipelineCanaryArtifactVault.open",
                "new PipelineCanaryEngine",
                "firstFrameProviderUrl",
                "video.submit",
                "video.poll(taskId)",
                "budget.reserve(\"KEYFRAME\"",
                "budget.reserve(\"VIDEO\"",
                "budget.reserve(\"TTS\"",
                "FFMPEG_PATH",
                "FFPROBE_PATH");
        assertThat(source.indexOf("new PipelineCanaryCapabilityGate")).isLessThan(source.indexOf("new VolcengineImageGenerator"));
        assertThat(source).doesNotContain("RUN_LIVE_PIPELINE_CANARY\",matches=\"(?i)false\"");
    }
}
