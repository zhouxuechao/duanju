package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.production.EvidenceLedger;
import com.yourapp.drama.production.SatisfactionEngine;
import org.springframework.stereotype.Component;

import java.util.List;

/** Deterministic blocking rules that must not depend on a model awarding itself a passing score. */
@Component
public class StoryQualityPolicy {
    private static final List<String> DELTAS = List.of("factsChanged", "relationshipsChanged", "goalsChanged",
            "knowledgeChanged", "riskChanged", "resourcesChanged");
    private static final List<String> PATTERN = List.of("episodeFunction", "hook", "mainConflict", "payoff", "cliffhanger");
    private final SatisfactionEngine satisfaction = new SatisfactionEngine();
    private final EvidenceLedger evidenceLedger = new EvidenceLedger();

    public ObjectNode apply(ObjectNode qa, JsonNode input) {
        recoverConfirmedDeltas(qa, input);
        boolean hasDelta = DELTAS.stream().anyMatch(key -> !qa.path("episodeDelta").path(key).isEmpty());
        if (!hasDelta) block(qa, "本集没有事实、关系、目标、知识、风险或资源变化，删除后不影响后续剧情",
                "保留已确认的 startState/endState，在当前集加入由角色选择造成且会被后续继承的真实变化");

        JsonNode outline = input.path("episodeOutline");
        long repeated = java.util.stream.StreamSupport.stream(input.path("recentStructuralSummaries").spliterator(), false)
                .filter(previous -> samePattern(outline, previous)).count();
        if (repeated >= 3) block(qa, "最近多集重复相同的开场、冲突、兑现和断章功能",
                "更换本集的冲突机制和兑现方式，并让新的选择改变关系、知识、资源或风险状态");

        JsonNode satisfactionContract=input.path("showrunnerContract").path("emotionContract").path("satisfactionContract");
        if(satisfactionContract.isObject()){
            SatisfactionEngine.Result satisfactionResult=satisfaction.evaluate(input.path("storyProfile").path("storyType").asText(),satisfactionContract,input.path("recentStructuralSummaries"));
            satisfactionResult.risks().forEach(risk->block(qa,"爽感合同："+risk.message(),"按本剧 StoryType 的情绪合同修正压力、信息差、兑现推进或重复方式"));
        }
        JsonNode ledger=input.path("episodeScript").path("evidenceLedger");
        if(ledger.isArray()&&!ledger.isEmpty()){
            for(var risk:evidenceLedger.validate(ledger,input.path("episodeScript").path("storyFacts"),input.path("episodeScript").path("characterKnowledge"),input.path("evidenceWorld"),input.path("episodeNo").asLong()))
                block(qa,"证据链："+risk.message(),"修正 Evidence、StoryFact、角色知情时间或证据持有人，使其不越过当前故事时间");
        }

        qa.put("passed", qa.path("passed").asBoolean() && qa.path("blockingIssues").isEmpty());
        qa.put("rewriteRequired", !qa.path("passed").asBoolean() || qa.path("rewriteRequired").asBoolean());
        return qa;
    }

    private void recoverConfirmedDeltas(ObjectNode qa, JsonNode input) {
        if (DELTAS.stream().anyMatch(key -> !qa.path("episodeDelta").path(key).isEmpty())) return;
        JsonNode outline = input.path("episodeOutline"), script = input.path("episodeScript");
        String outlineStart = outline.path("startState").asText("").trim();
        String outlineEnd = outline.path("endState").asText("").trim();
        if (outlineStart.isBlank() || outlineStart.equals(outlineEnd)
                || !outlineStart.equals(script.path("startState").asText("").trim())
                || !outlineEnd.equals(script.path("endState").asText("").trim())
                || !outline.path("progressionEvents").isArray()) return;
        ObjectNode delta = qa.withObject("episodeDelta");
        for (JsonNode event : outline.path("progressionEvents")) {
            String description = event.path("description").asText("").trim();
            if (description.isBlank()) continue;
            String type = event.path("type").asText("");
            String key = type.contains("知识") || type.contains("信息") || type.contains("身份") ? "knowledgeChanged"
                    : type.contains("关系") ? "relationshipsChanged"
                    : type.contains("风险") ? "riskChanged"
                    : type.contains("目标") ? "goalsChanged"
                    : type.contains("资源") || type.contains("道具") ? "resourcesChanged" : "factsChanged";
            ArrayNode values = delta.withArray(key);
            if (values.size() < 20 && !contains(values, description)) values.add(description);
        }
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
