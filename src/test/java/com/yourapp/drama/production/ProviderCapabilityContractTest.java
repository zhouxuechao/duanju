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
}
