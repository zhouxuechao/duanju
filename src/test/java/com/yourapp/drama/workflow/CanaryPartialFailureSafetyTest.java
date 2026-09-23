package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanaryPartialFailureSafetyTest {
    @TempDir Path temporary;

    @Test void imageSuccessThenCrashBlocksASecondImageSubmission(){
        Path file=temporary.resolve("provider-canary-state.json");
        LiveCanaryState first=LiveCanaryState.start(file,"run-image");
        first.advance("IMAGE_SUCCEEDED","image-request-1","");

        assertSecondStartBlocked(file);
        assertSafeState(first.snapshot());
        assertThat(first.snapshot().path("status").asText()).isEqualTo("IMAGE_SUCCEEDED");
        assertThat(first.snapshot().path("providerRequestId").asText()).isEqualTo("image-request-1");
    }

    @Test void submittedVideoPollTimeoutRequiresReconciliationAndPreservesProviderIds(){
        Path file=temporary.resolve("provider-canary-state.json");
        LiveCanaryState first=LiveCanaryState.start(file,"run-video");
        first.advance("VIDEO_SUBMITTED","video-request-1","video-task-1");
        first.fail(new AssertionError("TIMEOUT while polling existing provider task"),"","");

        ObjectNode snapshot=first.snapshot();
        assertThat(snapshot.path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(snapshot.path("providerRequestId").asText()).isEqualTo("video-request-1");
        assertThat(snapshot.path("providerTaskId").asText()).isEqualTo("video-task-1");
        assertSecondStartBlocked(file);
        assertSafeState(snapshot);
    }

    @Test void providerSuccessThenLocalValidationFailureIsDefinitiveFailure(){
        Path file=temporary.resolve("provider-canary-state.json");
        LiveCanaryState state=LiveCanaryState.start(file,"run-succeeded");
        state.advance("VIDEO_SUBMITTED","video-request-1","video-task-1");
        state.advance("VIDEO_SUCCEEDED","video-request-1","video-task-1");

        state.fail(new AssertionError("VIDEO_RESOLUTION_MISMATCH"),"","");

        assertThat(state.snapshot().path("status").asText()).isEqualTo("FAILED");
        assertThat(state.snapshot().path("providerRequestId").asText()).isEqualTo("video-request-1");
        assertThat(state.snapshot().path("providerTaskId").asText()).isEqualTo("video-task-1");
    }

    @Test void uncertainVideoSubmissionStillRequiresReconciliation(){
        Path file=temporary.resolve("provider-canary-state.json");
        LiveCanaryState state=LiveCanaryState.start(file,"run-uncertain");
        state.advance("VIDEO_SUBMITTING","image-request-1","");

        state.fail(new ProviderException("REQUEST_TIMEOUT","submit response lost","video-request-1",0,false,true),"","");

        assertThat(state.snapshot().path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(state.snapshot().path("providerRequestId").asText()).isEqualTo("video-request-1");
    }

    private void assertSecondStartBlocked(Path file){
        assertThatThrownBy(()->LiveCanaryState.start(file,"duplicate-run"))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("CANARY_RECONCILIATION_REQUIRED"));
    }

    private void assertSafeState(ObjectNode snapshot){
        assertThat(snapshot.fieldNames()).toIterable().allMatch(Set.of("runId","status","providerRequestId","providerTaskId","createdAt","updatedAt")::contains);
        assertThat(snapshot.toString()).doesNotContain("providerUrl","ARK_API_KEY","Authorization","https://","ark-");
    }
}
