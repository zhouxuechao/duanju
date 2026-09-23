package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanaryStateTest {
    @TempDir Path temporary;

    @Test void persistsFourShotProgressAndCanBeReopenedWithoutProviderUrls() throws Exception {
        Path file=temporary.resolve("pipeline-canary-state.json");
        PipelineCanaryState state=PipelineCanaryState.start(file,"pipeline-run-1","commit-1",List.of("shot-1","shot-2","shot-3","shot-4"));
        state.storyReady("project-1","episode-1","scene-1");
        state.beginKeyframe("shot-1");
        state.keyframeSucceeded("shot-1","image-request-1","keyframe-artifact-1","mock.volcengine.invalid","abc123");
        state.beginVideo("shot-1");
        state.videoSubmitted("shot-1","video-request-1","video-task-1");

        PipelineCanaryState reopened=PipelineCanaryState.open(file);
        ObjectNode snapshot=reopened.snapshot();

        assertThat(snapshot.path("phase").asText()).isEqualTo("PIPELINE");
        assertThat(snapshot.path("generationProfile").asText()).isEqualTo("TEST");
        assertThat(snapshot.path("projectId").asText()).isEqualTo("project-1");
        assertThat(snapshot.path("shots")).hasSize(4);
        assertThat(reopened.shot("shot-1").path("keyframeStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(reopened.shot("shot-1").path("keyframeProviderUrlFingerprint").asText()).isEqualTo("abc123");
        assertThat(reopened.shot("shot-1").path("videoStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(reopened.shot("shot-1").path("videoTaskId").asText()).isEqualTo("video-task-1");
        assertThat(Files.readString(file)).doesNotContain("https://","signature=","Authorization","Bearer","ARK_API_KEY");
    }

    @Test void rejectsUrlsAndASecondRunWhileDurableStateExists() {
        Path file=temporary.resolve("pipeline-canary-state.json");
        PipelineCanaryState state=PipelineCanaryState.start(file,"pipeline-run-1","commit-1",List.of("shot-1","shot-2","shot-3","shot-4"));
        state.beginKeyframe("shot-1");

        assertThatThrownBy(()->state.keyframeSucceeded("shot-1","request-1","artifact-1","https://signed.invalid/a","abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PipelineCanaryState.start(file,"pipeline-run-2","commit-2",List.of("shot-1","shot-2","shot-3","shot-4")))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("PIPELINE_CANARY_STATE_EXISTS"));
    }

    @Test void submittedVideoUncertaintyStopsTheWholePipelineAndPreservesIds() {
        Path file=temporary.resolve("pipeline-canary-state.json");
        PipelineCanaryState state=PipelineCanaryState.start(file,"pipeline-run-1","commit-1",List.of("shot-1","shot-2","shot-3","shot-4"));
        state.beginVideo("shot-2");
        state.videoSubmitted("shot-2","video-request-2","video-task-2");

        state.requireReconciliation("shot-2","VIDEO_POLL_UNKNOWN");

        assertThat(state.snapshot().path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(state.shot("shot-2").path("videoStatus").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(state.shot("shot-2").path("videoRequestId").asText()).isEqualTo("video-request-2");
        assertThat(state.shot("shot-2").path("videoTaskId").asText()).isEqualTo("video-task-2");
    }

    @Test void definitiveStepFailureDoesNotMasqueradeAsSubmissionUncertainty() {
        Path file=temporary.resolve("pipeline-canary-state.json");
        PipelineCanaryState state=PipelineCanaryState.start(file,"pipeline-run-1","commit-1",List.of("shot-1","shot-2","shot-3","shot-4"));
        state.beginKeyframe("shot-1");

        state.stepFailed("shot-1","keyframeStatus","KEYFRAME_REJECTED");

        assertThat(state.snapshot().path("status").asText()).isEqualTo("FAILED");
        assertThat(state.shot("shot-1").path("keyframeStatus").asText()).isEqualTo("FAILED");
        assertThat(state.shot("shot-1").path("failureCode").asText()).isEqualTo("KEYFRAME_REJECTED");
    }
}
