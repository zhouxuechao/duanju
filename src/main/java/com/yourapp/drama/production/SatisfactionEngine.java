package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Type-gated payoff checks. Non-payoff genres are deliberately left untouched. */
public final class SatisfactionEngine {
    private static final Set<String> ELIGIBLE = Set.of("COUNTERATTACK", "REVENGE", "IDENTITY_REVERSAL", "REBIRTH",
            "SON_IN_LAW", "DOMINANT_CEO", "POWER_FANTASY");
    private static final Set<String> ELIGIBLE_TROPES = Set.of("COUNTERATTACK", "REVENGE", "HIDDEN_IDENTITY", "REBIRTH",
            "SON_IN_LAW", "DOMINANT_CEO", "POWER_FANTASY");

    public record Result(boolean applicable, boolean passed, List<ProductionModels.Risk> risks) {}

    public boolean appliesTo(String storyType, Collection<String> tropes) {
        if (ELIGIBLE.contains(normalize(storyType))) return true;
        if (tropes != null) for (String trope : tropes) if (ELIGIBLE_TROPES.contains(normalize(trope))) return true;
        return false;
    }

    public Result evaluate(String storyType, JsonNode contract, JsonNode recentEpisodes) {
        boolean applicable = appliesTo(storyType, strings(contract.path("tropes")));
        if (!applicable) return new Result(false, true, List.of());
        List<ProductionModels.Risk> risks = new ArrayList<>();
        required(contract, "negativeEmotion", "NEGATIVE_EMOTION_MISSING", risks);
        required(contract, "informationGap", "INFORMATION_GAP_MISSING", risks);
        required(contract, "payoff", "PAYOFF_MISSING", risks);
        if (contract.path("amplifiers").isArray() && contract.path("amplifiers").isEmpty())
            error(risks, "INTENSITY_AMPLIFIER_MISSING", "satisfactionContract.amplifiers", "爽感合同缺少压力放大机制");
        int delay = contract.path("payoffDelayEpisodes").asInt(0);
        if (delay < 0) error(risks, "INVALID_PAYOFF_DELAY", "satisfactionContract.payoffDelayEpisodes", "兑现延迟不能为负数");

        List<JsonNode> recent = new ArrayList<>();
        if (recentEpisodes != null && recentEpisodes.isArray()) recentEpisodes.forEach(recent::add);
        int from = Math.max(0, recent.size() - 5);
        boolean pressure = false, payoffAdvanced = false;
        Set<String> payoffs = new HashSet<>();
        for (int i = from; i < recent.size(); i++) {
            JsonNode episode = recent.get(i);
            pressure |= episode.path("pressureIncreased").asBoolean(false)||!episode.path("escalation").asText("").isBlank();
            payoffAdvanced |= episode.path("payoffAdvanced").asBoolean(false)||!episode.path("payoff").asText("").isBlank();
            String payoff = episode.path("payoff").asText("").trim();
            if (!payoff.isBlank()) payoffs.add(payoff);
        }
        if (!recent.isEmpty() && !pressure) error(risks, "PRESSURE_NOT_ESCALATED", "recentEpisodes", "最近剧集没有增加压力");
        if (recent.size() >= Math.max(1, delay) && !payoffAdvanced) error(risks, "PAYOFF_STALLED", "recentEpisodes", "压力持续但兑现没有推进");
        if (recent.size() - from >= 3 && payoffs.size() == 1) error(risks, "MECHANICAL_PAYOFF_REPETITION", "recentEpisodes", "最近剧集重复同一种兑现方式");
        return new Result(true, risks.isEmpty(), List.copyOf(risks));
    }

    private static void required(JsonNode node, String field, String code, List<ProductionModels.Risk> risks) {
        if (node.path(field).asText("").isBlank()) error(risks, code, "satisfactionContract." + field, "爽感合同缺少 " + field);
    }
    private static List<String> strings(JsonNode values) { List<String> result = new ArrayList<>(); if (values.isArray()) values.forEach(v -> result.add(v.asText())); return result; }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private static void error(List<ProductionModels.Risk> risks, String code, String path, String message) { risks.add(new ProductionModels.Risk(code, "ERROR", path, message)); }
}
