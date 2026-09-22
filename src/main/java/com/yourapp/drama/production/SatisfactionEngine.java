package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates an explicit payoff contract. Applicability is data-driven by the contract's presence. */
public final class SatisfactionEngine {
    public record Result(boolean applicable, boolean passed, List<ProductionModels.Risk> risks) {}

    public Result evaluate(JsonNode contract, JsonNode recentEpisodes) {
        if(contract==null||!contract.isObject())return new Result(false,true,List.of());
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
    private static void error(List<ProductionModels.Risk> risks, String code, String path, String message) { risks.add(new ProductionModels.Risk(code, "ERROR", path, message)); }
}
