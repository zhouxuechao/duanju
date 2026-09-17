package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoTake(String id, String projectId, String shotId, int takeNo, String provider,
                        String model, String promptVersionId, String providerRequestId,
                        String sourceKeyframeId, String sourceProviderUrlSnapshot,
                        String videoUrl, String archiveUrl, String providerStatus, String qcStatus,
                        Double qcScore, boolean selected, boolean locked, BigDecimal cost,
                        long revision, Instant createdAt) {}
