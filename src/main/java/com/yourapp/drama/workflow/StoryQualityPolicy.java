package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** Deterministic blocking rules that must not depend on a model awarding itself a passing score. */
@Component
public class StoryQualityPolicy {
    private static final List<String> DELTAS = List.of("factsChanged", "relationshipsChanged", "goalsChanged",
            "knowledgeChanged", "riskChanged", "resourcesChanged");
    private static final List<String> PATTERN = List.of("episodeFunction", "hook", "mainConflict", "payoff", "cliffhanger");

    public ObjectNode apply(ObjectNode qa, JsonNode input) {
        boolean hasDelta = DELTAS.stream().anyMatch(key -> !qa.path("episodeDelta").path(key).isEmpty());
        if (!hasDelta) block(qa, "本集没有事实、关系、目标、知识、风险或资源变化，删除后不影响后续剧情",
                "保留已确认的 startState/endState，在当前集加入由角色选择造成且会被后续继承的真实变化");

        JsonNode outline = input.path("episodeOutline");
        long repeated = java.util.stream.StreamSupport.stream(input.path("recentStructuralSummaries").spliterator(), false)
                .filter(previous -> samePattern(outline, previous)).count();
        if (repeated >= 3) block(qa, "最近多集重复相同的开场、冲突、兑现和断章功能",
                "更换本集的冲突机制和兑现方式，并让新的选择改变关系、知识、资源或风险状态");

        qa.put("passed", qa.path("passed").asBoolean() && qa.path("blockingIssues").isEmpty());
        qa.put("rewriteRequired", !qa.path("passed").asBoolean() || qa.path("rewriteRequired").asBoolean());
        return qa;
    }

    private boolean samePattern(JsonNode current, JsonNode previous) {
        return PATTERN.stream().allMatch(field -> normalize(current.path(field).asText()).equals(normalize(previous.path(field).asText())));
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\p{S}\\s\\d]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private void block(ObjectNode qa, String issue, String rewrite) {
        ArrayNode blocking = qa.withArray("blockingIssues");
        if (!contains(blocking, issue)) blocking.add(issue);
        ArrayNode instructions = qa.withArray("rewriteInstructions");
        if (!contains(instructions, rewrite)) instructions.add(rewrite);
        qa.withObject("dimensions").put("narrativeNecessity",
                Math.min(qa.path("dimensions").path("narrativeNecessity").asInt(100), 39));
    }

    private boolean contains(ArrayNode values, String expected) {
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }
}
