package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerationJob(String id, String localTaskId, String projectId, String shotId, JobType type, JobStatus status,
                            String phase, String provider, String model, String providerRequestId, String providerTaskId, String requestKey,
                            JsonNode inputSnapshot, JsonNode outputSnapshot, String failureReason,
                            int attempts, int retryCount, int maxAttempts, double progress, long elapsedMs, BigDecimal cost,
                            boolean cancelRequested, Instant retryAt, long revision,
                            Instant createdAt, Instant updatedAt) {}
