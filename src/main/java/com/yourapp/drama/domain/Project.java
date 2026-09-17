package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(String id, String name, String idea, String genre, int episodeCount,
                      double targetDuration, String ratio, String style, String dialect,
                      String status, long revision, Instant createdAt, Instant updatedAt) {}
