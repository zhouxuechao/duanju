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
    public enum ActionHand { NONE, LEFT, RIGHT, BOTH }
    /** A replayable action cursor. Progress is monotonic from 0 to 1 for one action/hand/object tuple. */
    public record ActionState(String action, double progress, ActionHand hand, String object) {}
    public record ContinuityPlan(JsonNode startState, JsonNode inheritedConstraints,
                                 Set<String> requiredAssets, List<String> allowedChanges,
                                 List<Risk> risks, int riskScore, boolean passed) {}
    /** Provider-neutral intermediate representation rendered by PromptCompiler. */
    public record PromptIR(String taskType, JsonNode shotIntent, JsonNode characterConstraints,
                           JsonNode locationConstraints, JsonNode propConstraints, JsonNode subject,
                           JsonNode identity, JsonNode costume, JsonNode environment, JsonNode action,
                           JsonNode emotion, JsonNode blocking, JsonNode camera, JsonNode lighting,
                           JsonNode continuity, JsonNode audio, JsonNode dialogue,
                           List<String> negativeConstraints, JsonNode output, List<JsonNode> references) {}
    public record PromptResult(String compilerVersion, String modelFamily, String prompt,
                               List<JsonNode> references, ContinuityPlan continuity,
                               Strategy strategy, int recommendedTakes, PromptIR promptIR) {}
    /** Compiled prompt for production tasks that are not shot generation but still require formal PromptIR. */
    public record AuxiliaryPrompt(String compilerVersion, String modelFamily, String prompt,
                                  PromptIR promptIR, JsonNode promptIRJson) {}
    /** Audited structured text request. Provider adapters only serialize these fields. */
    public record StructuredPrompt(String compilerVersion, String modelFamily, String systemPrompt,
                                   String userPrompt, JsonNode schema, PromptIR promptIR, JsonNode promptIRJson) {}
    public record DialogueTracks(String semanticText, String spokenText, String subtitleText,
                                  String dialect, double dialectStrength, double confidence,
                                  boolean needsHumanCorrection, List<String> matchedEntryIds,
                                  List<JsonNode> suggestions, String status) {}
    public record SubtitleCue(String dialogueId, String subtitleText, long startMs, long endMs) {}
    public record TimelinePlan(boolean passed, double duration, List<Risk> risks,
                               List<String> arguments, String filterGraph, String srt,
                               String subtitleMode, String outputPath) {}
}
