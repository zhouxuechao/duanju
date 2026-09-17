package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Append-only compiler output and source context, retained for local regeneration. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromptVersion(String id, String projectId, String shotId, int version,
                            String promptTemplateId, String purpose, String prompt,
                            String compilerVersion, JsonNode inputSnapshot, Instant createdAt) {}
