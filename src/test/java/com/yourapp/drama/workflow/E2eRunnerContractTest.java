package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class E2eRunnerContractTest {
    @Test void canaryRunsTheOptInLiveProviderTestInsteadOfLocalAdapterMocks() throws Exception {
        String runner=Files.readString(Path.of("scripts/e2e.ps1"));
        assertThat(runner).contains("LiveProviderCanaryIT");
        assertThat(runner).doesNotContain("else{'VolcengineAdaptersTest,SeedAudioVoiceGeneratorTest'}");
    }
}
