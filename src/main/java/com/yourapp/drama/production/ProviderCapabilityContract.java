package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static com.yourapp.drama.workflow.Documents.obj;

/** Converts explicit Live Canary evidence into an auditable capability snapshot. */
public final class ProviderCapabilityContract {
    public ObjectNode verify(VideoModelProfile profile, JsonNode evidence) {
        requireText(evidence, "provider", "VOLCENGINE");
        requireText(evidence, "model", profile.modelId());
        requireText(evidence, "evidenceSource", "LIVE_CANARY");
        try { Instant.parse(required(evidence, "checkedAt")); }
        catch (RuntimeException error) { throw invalid("checkedAt must be an ISO-8601 instant"); }
        JsonNode expected = profile.toJson();
        requireEqual(evidence, expected, "supportedDurations");
        requireEqual(evidence, expected, "durationMode");
        requireEqual(evidence, expected, "minDuration");
        requireEqual(evidence, expected, "maxDuration");
        requireEqual(evidence, expected, "durationStep");
        requireEqual(evidence, expected, "supportedResolutions");
        requireEqual(evidence, expected, "supportedRatios");
        requireEqual(evidence, expected, "supportedTaskTypes");
        if (!evidence.path("referenceLimits").equals(expected.path("hardLimits"))) throw invalid("referenceLimits differ from the configured profile");
        for (String feature : List.of("firstFrame", "firstLastFrame", "referenceImage", "referenceVideo", "referenceAudio", "nativeAudio", "providerOptions"))
            if (!evidence.path(feature).asBoolean(false)) throw invalid(feature + " was not verified");
        if (!evidence.path("providerRequestIds").isArray() || evidence.path("providerRequestIds").isEmpty()) throw invalid("providerRequestIds are required");
        ObjectNode snapshot = obj().put("provider", "VOLCENGINE").put("model", profile.modelId())
                .put("checkedAt", required(evidence, "checkedAt")).put("verificationStatus", "LIVE_VERIFIED")
                .put("profileVersion", profile.profileVersion()).put("capabilityFingerprint", profile.capabilityFingerprint());
        for (String field : List.of("supportedDurations", "durationMode", "minDuration", "maxDuration", "durationStep", "supportedResolutions", "supportedRatios", "supportedTaskTypes")) snapshot.set(field, expected.path(field).deepCopy());
        snapshot.set("referenceLimits", expected.path("hardLimits").deepCopy());
        snapshot.set("providerRequestIds", evidence.path("providerRequestIds").deepCopy());
        return snapshot;
    }

    private void requireEqual(JsonNode actual, JsonNode expected, String field) {
        if (!actual.path(field).equals(expected.path(field))) throw invalid(field + " differ from the configured profile");
    }
    private void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) throw invalid(field + " must be " + expected);
    }
    private String required(JsonNode node, String field) {
        String value=node.path(field).asText();if(value.isBlank())throw invalid(field+" is required");return value;
    }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException("CAPABILITY_EVIDENCE_INVALID: " + message); }
}
