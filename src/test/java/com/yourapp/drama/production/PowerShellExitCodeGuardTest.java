package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PowerShellExitCodeGuardTest {
    @Test void optionalCommandsCheckLastExitCodeOnlyInsideTheExecutedBranch() throws Exception {
        for(String file:java.util.List.of("scripts/run.ps1","scripts/test.ps1")){
            String script=Files.readString(Path.of(file)).replace("\r\n","\n");
            assertThat(script).as(file)
                .doesNotContain("if (-not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci }\n    if ($LASTEXITCODE")
                .containsPattern("(?s)& npm\\.cmd ci\\s+if \\(\\$LASTEXITCODE");
        }
    }
}
