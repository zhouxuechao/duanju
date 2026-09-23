package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryJarLockIsolationTest {
    @Test void ordinaryRegressionStillRunsTheFullVerifyLifecycle() throws Exception {
        String script=script("test.ps1");

        assertThat(script).contains("[switch]$SkipPackage");
        assertThat(script).contains("& mvn -B -ntp verify");
    }

    @Test void canaryRegressionSkipsOnlySpringBootRepackage() throws Exception {
        String script=script("test.ps1");

        assertThat(script).contains("if($SkipPackage)");
        assertThat(script).contains("& mvn -B -ntp verify '-Dspring-boot.repackage.skip=true'");
    }

    @Test void canaryRegressionStillRunsFrontendTestsAndBuildBeforeBackendTests() throws Exception {
        String script=script("test.ps1");
        int frontendTests=script.indexOf("npm.cmd run test");
        int frontendBuild=script.indexOf("npm.cmd run build");
        int backend=script.indexOf("mvn -B -ntp verify '-Dspring-boot.repackage.skip=true'");

        assertThat(frontendTests).isGreaterThanOrEqualTo(0);
        assertThat(frontendBuild).isGreaterThan(frontendTests);
        assertThat(backend).isGreaterThan(frontendBuild);
    }

    @Test void runnerUsesJarLockSafeRegressionBeforeActivatingCanary() throws Exception {
        String runner=script("run-provider-canary.ps1");
        int jobsPreflight=runner.indexOf("Invoke-RestMethod -Method Get -Uri \"$BaseUrl/api/resources/jobs\"");
        int regression=runner.indexOf("test.ps1') -SkipPackage");
        int activate=runner.indexOf("Import-CanaryEnvironment -Path $EnvFile");
        int authorize=runner.indexOf("$env:CANARY_RUNNER_PREFLIGHT_OK='true'");
        int live=runner.indexOf("-Dtest=LiveProviderCanaryIT");

        assertThat(jobsPreflight).isGreaterThanOrEqualTo(0);
        assertThat(regression).isGreaterThan(jobsPreflight);
        assertThat(activate).isGreaterThan(regression);
        assertThat(authorize).isGreaterThan(activate);
        assertThat(live).isGreaterThan(authorize);
    }

    private String script(String name) throws Exception {
        return Files.readString(Path.of("scripts",name)).replace("\r\n","\n");
    }
}
