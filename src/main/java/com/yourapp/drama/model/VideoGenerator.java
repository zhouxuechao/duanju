package com.yourapp.drama.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface VideoGenerator {
    Submission submit(VideoRequest request);
    VideoTask poll(String taskId);
    void cancel(String taskId);

    record VideoRequest(String prompt, String firstFrameUrl, List<Reference> references,
                        Map<String, Object> options) {
        public VideoRequest {
            references = references == null ? List.of() : List.copyOf(references);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }
    record Reference(String type, String url, String role) {}
    record Submission(String taskId, String requestId, boolean simulated) {}
    record VideoTask(String taskId, Status status, String providerUrl, Instant expiresAt,
                     String requestId, String errorCode, String errorMessage, boolean simulated) {}
    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED }
}
