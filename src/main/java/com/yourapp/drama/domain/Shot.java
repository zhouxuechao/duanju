package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Durable shot projection; DirectorContract builds the strict output schema from approved asset IDs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Shot(String id, String projectId, String sceneId, int shotNo,
                   @NotBlank String purpose, @DecimalMin("2.0") @DecimalMax("5.0") double duration,
                   List<String> characterIds, String locationId, List<String> propIds,
                   String shotSize, String cameraAngle, String cameraMovement,
                   @NotBlank String action, String visualFocus, String emotion, List<String> dialogueIds,
                   JsonNode startState, JsonNode endState, ShotRelation relationToPrevious,
                   Difficulty difficulty, ShotStatus status, long revision, Instant createdAt, Instant updatedAt) {
    public enum Difficulty { A, B, C, D }
}
