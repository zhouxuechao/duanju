package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Immutable neighboring-episode contract used when regenerating one episode. */
public final class RewriteBoundary {
    private final ObjectMapper mapper;
    public RewriteBoundary(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode build(int episodeNo, JsonNode previousAccepted, JsonNode current, JsonNode nextExisting) {
        ObjectNode result = mapper.createObjectNode().put("episodeNo", episodeNo)
                .put("previousAcceptedEndState", previousAccepted.path("endState").asText(""))
                .put("currentStartState", current.path("startState").asText(""))
                .put("currentEndState", current.path("endState").asText(""))
                .put("nextExistingStartState", nextExisting.path("startState").asText(""));
        ObjectNode protectedState = result.putObject("protected");
        for (String field : List.of("establishedFacts", "unrevealedSecrets", "characterKnowledge", "evidenceIds", "relationships"))
            protectedState.set(field, current.path(field).deepCopy());
        protectedState.set("pastEstablishedFacts", previousAccepted.path("establishedFacts").deepCopy());
        return result;
    }

    public List<ProductionModels.Risk> validate(JsonNode candidate, JsonNode boundary) {
        List<ProductionModels.Risk> risks = new ArrayList<>();
        String previousEnd = boundary.path("previousAcceptedEndState").asText("");
        if (!previousEnd.isBlank() && !previousEnd.equals(candidate.path("startState").asText("")))
            error(risks, "PREVIOUS_END_STATE_BROKEN", "startState", "改写后的开场状态与上一集已确认结尾不一致");
        String nextStart = boundary.path("nextExistingStartState").asText("");
        String originalEnd = boundary.path("currentEndState").asText("");
        if (!nextStart.isBlank() && !originalEnd.equals(candidate.path("endState").asText("")))
            error(risks, "NEXT_START_CONTRACT_BROKEN", "endState", "改写改变了下一集依赖的既有结尾合同");
        for (String field : List.of("unrevealedSecrets", "characterKnowledge", "evidenceIds", "relationships")) {
            JsonNode expected = boundary.path("protected").path(field), actual = candidate.path(field);
            if (!expected.isMissingNode() && !expected.isNull() && actual.isArray() && !expected.equals(actual))
                error(risks, "PROTECTED_" + field.toUpperCase() + "_CHANGED", field, "改写越过了受保护的连续性边界");
        }
        return List.copyOf(risks);
    }

    private static void error(List<ProductionModels.Risk> risks, String code, String path, String message) { risks.add(new ProductionModels.Risk(code, "ERROR", path, message)); }
}
