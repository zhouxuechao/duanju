package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Episode(String id, String projectId, String name, int episodeNo, String summary,
                      String script, long revision, Instant createdAt, Instant updatedAt) {}
