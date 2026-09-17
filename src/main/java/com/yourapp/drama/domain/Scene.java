package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Scene(String id, String projectId, String episodeId, String name, int sceneNo,
                    String description, double duration, JsonNode state,
                    long revision, Instant createdAt, Instant updatedAt) {}
