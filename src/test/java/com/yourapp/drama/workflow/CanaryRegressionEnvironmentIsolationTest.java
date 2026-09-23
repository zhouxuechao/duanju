package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryRegressionEnvironmentIsolationTest {
    @Test void ordinaryRegressionForcesMockProviderAndFakeReviewersBeforeAnyTests() throws Exception {
        String script=script("test.ps1");
        int mock=script.indexOf("$env:DRAMA_PROVIDER_MODE='mock'");
        int fake=script.indexOf("$env:VISUAL_REVIEWER='fake'");
        int frontend=script.indexOf("npm.cmd run test");
        int backend=script.indexOf("mvn -B -ntp verify");

        assertThat(mock).isGreaterThanOrEqualTo(0).isLessThan(frontend);
        assertThat(fake).isGreaterThanOrEqualTo(0).isLessThan(frontend);
        assertThat(backend).isGreaterThan(frontend);
        assertThat(mock).isLessThan(backend);
        assertThat(fake).isLessThan(backend);
    }

    @Test void ordinaryRegressionCannotInheritLiveAuthorization() throws Exception {
        String script=script("test.ps1");
        int providerOff=script.indexOf("$env:RUN_LIVE_PROVIDER_CANARY='false'");
        int pipelineOff=script.indexOf("$env:RUN_LIVE_PIPELINE_CANARY='false'");
        int markerRemoved=script.indexOf("Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK");
        int frontend=script.indexOf("npm.cmd run test");

        assertThat(providerOff).isGreaterThanOrEqualTo(0).isLessThan(frontend);
        assertThat(pipelineOff).isGreaterThanOrEqualTo(0).isLessThan(frontend);
        assertThat(markerRemoved).isGreaterThanOrEqualTo(0).isLessThan(frontend);
    }

    @Test void ordinaryRegressionRestoresTheCallersProcessEnvironment() throws Exception {
        String script=script("test.ps1");
        int backend=script.indexOf("mvn -B -ntp verify");
        int restore=script.lastIndexOf("[Environment]::SetEnvironmentVariable($taskName,$taskOriginalEnvironment[$taskName],'Process')");

        assertThat(restore).isGreaterThan(backend);
    }

    @Test void runnerActivatesCanaryEnvironmentOnlyAfterRegressionPasses() throws Exception {
        String runner=script("run-provider-canary.ps1");
        int regression=runner.indexOf("test.ps1");
        int activate=runner.indexOf("Import-CanaryEnvironment -Path $EnvFile");
        int authorize=runner.indexOf("$env:CANARY_RUNNER_PREFLIGHT_OK='true'");
        int live=runner.indexOf("-Dtest=LiveProviderCanaryIT");

        assertThat(regression).isGreaterThanOrEqualTo(0);
        assertThat(activate).isGreaterThan(regression);
        assertThat(authorize).isGreaterThan(activate);
        assertThat(live).isGreaterThan(authorize);
        assertThat(runner.substring(0,regression)).doesNotContain("SetEnvironmentVariable($matches[1].Trim()");
    }

    private String script(String name){
        try{return Files.readString(Path.of("scripts",name)).replace("\r\n","\n");}
        catch(Exception error){throw new IllegalStateException(error);}
    }
}
