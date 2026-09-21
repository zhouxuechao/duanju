package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestScriptContractTest {
    @Test
    void unifiedTestScriptRunsVitestBeforeFrontendBuildAndChecksItsExitCode() throws Exception {
        String script = Files.readString(Path.of("scripts", "test.ps1")).replace("\r\n", "\n");
        int vitest = script.indexOf("npm.cmd run test");
        int build = script.indexOf("npm.cmd run build");

        assertThat(vitest).as("scripts/test.ps1 must execute frontend Vitest").isGreaterThanOrEqualTo(0);
        assertThat(build).as("scripts/test.ps1 must execute the frontend build").isGreaterThan(vitest);
        assertThat(script.substring(vitest, build))
            .as("Vitest failures must stop the unified test run before building")
            .contains("$LASTEXITCODE -ne 0");
    }
}
