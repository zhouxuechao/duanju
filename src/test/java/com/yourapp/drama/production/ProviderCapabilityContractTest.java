package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCapabilityContractTest {
    private final ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry();

    @Test void onlyExplicitlyVerifiedModelIdsHaveProfiles() {
        assertThat(registry.profile("doubao-seedance-2-0").family()).isEqualTo("SEEDANCE_2_0");
        assertThat(registry.profile("doubao-seedance-2-5-260628").family()).isEqualTo("SEEDANCE_2_5");
        assertThatThrownBy(() -> registry.profile("doubao-seedance-2-5-future"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNVERIFIED_PROVIDER_MODEL");
    }

    @Test void liveEvidenceProducesAuditableCapabilitySnapshot() {
        VideoModelProfile profile = registry.profile("doubao-seedance-2-5-260628");
        ObjectNode evidence = obj().put("provider", "VOLCENGINE").put("model", profile.modelId())
                .put("checkedAt", Instant.parse("2026-09-23T00:00:00Z").toString())
                .put("evidenceSource", "LIVE_CANARY");
        evidence.set("supportedDurations", profile.toJson().path("supportedDurations").deepCopy());
        for(String field:java.util.List.of("durationMode","minDuration","maxDuration","durationStep"))evidence.set(field,profile.toJson().path(field).deepCopy());
        evidence.set("supportedResolutions", profile.toJson().path("supportedResolutions").deepCopy());
        evidence.set("supportedRatios", profile.toJson().path("supportedRatios").deepCopy());
        evidence.set("referenceLimits", profile.toJson().path("hardLimits").deepCopy());
        evidence.set("supportedTaskTypes", profile.toJson().path("supportedTaskTypes").deepCopy());
        evidence.put("firstFrame", true).put("firstLastFrame", true).put("referenceImage", true)
                .put("referenceVideo", true).put("referenceAudio", true).put("nativeAudio", true)
                .put("providerOptions", true);
        evidence.putArray("providerRequestIds").add("live-request-1");

        ObjectNode snapshot = new ProviderCapabilityContract().verify(profile, evidence);

        assertThat(snapshot.path("verificationStatus").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(snapshot.path("capabilityFingerprint").asText()).hasSize(64);
        assertThat(snapshot.path("profileVersion").asText()).isEqualTo(profile.profileVersion());
        assertThat(snapshot.path("providerRequestIds").get(0).asText()).isEqualTo("live-request-1");
    }

    @Test void phaseACanaryOnlyVerifiesTheExactImageAndVideoOutputThatWasObserved() {
        ImageModelProfile image = registry.imageProfile(ProviderCapabilityRegistry.SEEDREAM_50);
        VideoModelProfile video = registry.profile(ProviderCapabilityRegistry.SEEDANCE_20_FAST);
        ObjectNode evidence = obj().put("provider", "VOLCENGINE").put("evidenceSource", "LIVE_CANARY")
                .put("checkedAt", "2026-09-23T00:00:00Z");
        evidence.set("image", obj().put("modelId", image.modelId()).put("providerRequestId", "image-request-1")
                .put("requestedImageQuality", "2K").put("aspectRatioIntent", "9:16").put("providerSize", "2K")
                .put("providerReturnedWidth", 1152).put("providerReturnedHeight", 2048));
        evidence.set("video", obj().put("modelId", video.modelId()).put("providerRequestId", "video-request-1")
                .put("providerTaskId", "video-task-1").put("requestedResolution", "480p").put("requestedDuration", 5)
                .put("actualWidth", 480).put("actualHeight", 854).put("actualDurationMs", 5040)
                .put("providerUrlHandoff", true).put("firstFrameFingerprint", "same-fingerprint")
                .put("sourceImageFingerprint", "same-fingerprint"));

        ObjectNode snapshot = new ProviderCapabilityContract().verifyCanary(image, video, evidence);

        assertThat(snapshot.path("verificationStatus").asText()).isEqualTo("PARTIAL_LIVE_VERIFIED");
        assertThat(snapshot.path("imageOutputs").path("2K").path("9:16").path("status").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(snapshot.path("imageOutputs").path("2K").path("16:9").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.path("videoOutputs").path("480p").path("5s").path("status").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(snapshot.path("videoOutputs").path("480p").path("11s").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.path("videoOutputs").path("720p").path("5s").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.toString()).doesNotContain("https://", "\"providerUrl\":");
    }

    @Test void phaseACanaryDoesNotUpgradeACombinationWhenObservedMediaDoesNotMatchTheRequest() {
        ImageModelProfile image = registry.imageProfile(ProviderCapabilityRegistry.SEEDREAM_50);
        VideoModelProfile video = registry.profile(ProviderCapabilityRegistry.SEEDANCE_20_FAST);
        ObjectNode evidence = obj().put("provider", "VOLCENGINE").put("evidenceSource", "LIVE_CANARY")
                .put("checkedAt", "2026-09-23T00:00:00Z");
        evidence.set("image", obj().put("modelId", image.modelId()).put("providerRequestId", "image-request-1")
                .put("requestedImageQuality", "2K").put("aspectRatioIntent", "9:16").put("providerSize", "2K")
                .put("providerReturnedWidth", 2048).put("providerReturnedHeight", 2048));
        evidence.set("video", obj().put("modelId", video.modelId()).put("providerRequestId", "video-request-1")
                .put("providerTaskId", "video-task-1").put("requestedResolution", "480p").put("requestedDuration", 5)
                .put("actualWidth", 720).put("actualHeight", 1280).put("actualDurationMs", 9000)
                .put("providerUrlHandoff", false).put("firstFrameFingerprint", "a").put("sourceImageFingerprint", "b"));

        ObjectNode snapshot = new ProviderCapabilityContract().verifyCanary(image, video, evidence);

        assertThat(snapshot.path("verificationStatus").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.path("imageOutputs").path("2K").path("9:16").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.path("videoOutputs").path("480p").path("5s").path("status").asText()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(snapshot.path("failures")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                .contains("IMAGE_ASPECT_RATIO_MISMATCH", "VIDEO_RESOLUTION_MISMATCH", "VIDEO_DURATION_MISMATCH", "PROVIDER_URL_HANDOFF_MISMATCH");
    }
}
