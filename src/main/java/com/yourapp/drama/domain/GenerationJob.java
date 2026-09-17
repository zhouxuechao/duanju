package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerationJob(String id, String projectId, String shotId, JobType type, JobStatus status,
                            String providerRequestId, String providerTaskId, String requestKey,
                            JsonNode inputSnapshot, JsonNode outputSnapshot, String failureReason,
                            int attempts, int maxAttempts, double progress, BigDecimal cost,
                            boolean cancelRequested, Instant retryAt, long revision,
                            Instant createdAt, Instant updatedAt) {}
