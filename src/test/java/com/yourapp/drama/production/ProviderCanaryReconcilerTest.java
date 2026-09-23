package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.LiveCanaryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCanaryReconcilerTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void existingEvidenceIsReplayedWithoutProviderCallsAndStateIsReconciled() throws Exception {
        Fixture fixture=fixture("video-task-1");
        String original=Files.readString(fixture.evidence(),StandardCharsets.UTF_8);

        ProviderCanaryReconciler.Result result=new ProviderCanaryReconciler(mapper,new ProviderCapabilityRegistry())
                .reconcile(fixture.evidence(),fixture.state(),fixture.snapshot(),fixture.reconciledEvidence(),fixture.reports());

        assertThat(Files.readString(fixture.evidence(),StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(LiveCanaryState.open(fixture.state()).snapshot().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.capability().path("verificationStatus").asText()).isEqualTo("PARTIAL_LIVE_VERIFIED");
        assertThat(result.capability().path("imageOutputs").path("2K").path("9:16").path("status").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(result.capability().path("videoOutputs").path("480p").path("5s").path("status").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(result.capability().path("videoOutputs").path("720p").path("5s").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        ObjectNode replay=(ObjectNode)mapper.readTree(fixture.reconciledEvidence().toFile());
        assertThat(replay.path("originalCanaryStatus").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(replay.path("reconciliationMode").asText()).isEqualTo("OFFLINE_EVIDENCE_REPLAY");
        assertThat(replay.path("providerRequestsDuringReconciliation").asInt()).isZero();
        assertThat(replay.path("billingStatus").asText()).isEqualTo("UNPRICED");
        assertThat(replay.path("providerActualCost").asText()).isEqualTo("UNKNOWN");
        assertThat(replay.path("budgetSettledEstimate").asDouble()).isEqualTo(5.0);
        assertThat(replay.has("actualCost")).isFalse();
        assertThat(Files.readString(result.report())).contains("OFFLINE_EVIDENCE_REPLAY","Provider requests during reconciliation: `0`","Budget settled estimate: `5.0`","Provider actual cost: `UNKNOWN`");
    }

    @Test void identityMismatchStopsWithoutChangingStateOrWritingResults() throws Exception {
        Fixture fixture=fixture("different-video-task");

        assertThatThrownBy(()->new ProviderCanaryReconciler(mapper,new ProviderCapabilityRegistry())
                .reconcile(fixture.evidence(),fixture.state(),fixture.snapshot(),fixture.reconciledEvidence(),fixture.reports()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");

        assertThat(LiveCanaryState.open(fixture.state()).snapshot().path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(fixture.snapshot()).doesNotExist();
        assertThat(fixture.reconciledEvidence()).doesNotExist();
    }

    @Test void offlineRunnerCannotEnableOrInvokeLiveProvider() throws Exception {
        String script=Files.readString(Path.of("scripts","reconcile-provider-canary.ps1"));

        assertThat(script).contains("$env:RUN_LIVE_PROVIDER_CANARY='false'","OfflineProviderCanaryReconciliationIT");
        assertThat(script).doesNotContain("-ConfirmLive","run-provider-canary.ps1","ARK_API_KEY","VolcengineImageGenerator","VolcengineVideoGenerator");
        assertThat(ProviderCanaryReconciler.class.getDeclaredFields()).allMatch(field->!field.getType().getName().contains("provider"));
    }

    private Fixture fixture(String evidenceTaskId) throws Exception {
        Path state=temporary.resolve("provider-canary-state.json"),evidence=temporary.resolve("provider-canary-evidence.json");
        LiveCanaryState live=LiveCanaryState.start(state,"run-1");
        live.advance("VIDEO_SUBMITTED","video-request-1","video-task-1");
        live.advance("RECONCILIATION_REQUIRED","","");
        ObjectNode root=obj().put("provider","VOLCENGINE").put("evidenceSource","LIVE_CANARY").put("phase","PROVIDER")
                .put("generationProfile","TEST").put("runId","run-1").put("status","FAILED").put("checkedAt","2026-09-23T10:46:41Z")
                .put("retryCount",0).put("billingStatus","UNPRICED");
        root.set("image",obj().put("modelId",ProviderCapabilityRegistry.SEEDREAM_50).put("providerRequestId","image-request-1")
                .put("requestedImageQuality","2K").put("aspectRatioIntent","9:16").put("providerSize","2K")
                .put("providerReturnedWidth",1600).put("providerReturnedHeight",2848));
        root.set("video",obj().put("modelId",ProviderCapabilityRegistry.SEEDANCE_20_FAST).put("providerRequestId","video-request-1")
                .put("providerTaskId",evidenceTaskId).put("providerStatus","SUCCEEDED").put("requestedResolution","480p")
                .put("requestedDuration",5).put("ratio","9:16").put("actualWidth",496).put("actualHeight",864)
                .put("actualDurationMs",5042).put("frameRate",24).put("hasAudio",false).put("providerUrlHandoff",true)
                .put("firstFrameFingerprint","same-fingerprint").put("sourceImageFingerprint","same-fingerprint"));
        root.set("budget",obj().put("plannedCost",5).put("reservedCost",0).put("actualCost",5).put("wasteCost",0).put("image",1).put("video",1));
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(),root);
        return new Fixture(evidence,state,temporary.resolve("provider-capability-snapshot.json"),temporary.resolve("provider-canary-reconciled-evidence.json"),temporary.resolve("reports"));
    }

    private record Fixture(Path evidence,Path state,Path snapshot,Path reconciledEvidence,Path reports){}
}
