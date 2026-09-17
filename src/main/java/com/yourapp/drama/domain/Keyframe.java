package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Keyframe(String id, String projectId, String shotId, int version,
                       String provider, String sourceModel, String providerUrl,
                       Instant providerUrlExpiresAt, String archiveUrl, String generationJobId,
                       String providerRequestId, HandoffStatus handoffStatus, String qcStatus,
                       boolean selected, boolean locked, long revision, Instant createdAt) {}
