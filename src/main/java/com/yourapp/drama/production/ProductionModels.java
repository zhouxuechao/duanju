package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.domain.ShotRelation;
import java.util.List;
import java.util.Set;

/** Provider-independent production contracts. Narrative text never substitutes for a Shot. */
public final class ProductionModels {
    private ProductionModels() {}
    public enum Strategy { INDEPENDENT_CUT, CONTINUATION, REANCHOR_AFTER_DRIFT, MOTION_REFERENCE }
    public record Shot(String shotId, String purpose, double duration, List<String> characterIds,
                       String locationId, List<String> propIds, String shotSize, String cameraAngle,
                       String cameraMovement, String action, String visualFocus, String emotion,
                       List<String> dialogueIds, JsonNode startState, JsonNode endState,
                       ShotRelation relationToPrevious, com.yourapp.drama.domain.Shot.Difficulty difficulty) {}
    public record Risk(String code, String severity, String path, String message) {}
    public record ContinuityPlan(JsonNode startState, JsonNode inheritedConstraints,
                                 Set<String> requiredAssets, List<String> allowedChanges,
                                 List<Risk> risks, int riskScore, boolean passed) {}
    public record PromptResult(String compilerVersion, String modelFamily, String prompt,
                               List<JsonNode> references, ContinuityPlan continuity,
                               Strategy strategy, int recommendedTakes) {}
    public record DialogueTracks(String displayText, String dialectText, String speechText,
                                  String dialect, double dialectStrength, double confidence,
                                  boolean needsHumanCorrection, List<String> matchedEntryIds,
                                  List<JsonNode> suggestions, String status) {}
    public record SubtitleCue(String dialogueId, String displayText, long startMs, long endMs) {}
    public record TimelinePlan(boolean passed, double duration, List<Risk> risks,
                               List<String> arguments, String filterGraph, String srt,
                               String subtitleMode, String outputPath) {}
}
