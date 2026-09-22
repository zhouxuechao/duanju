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
    private final HookRule hookRule = new HookRule();
    private final EpisodeEndingRule endingRule = new EpisodeEndingRule();

    public ObjectNode apply(ObjectNode qa, JsonNode input) {
        recoverConfirmedDeltas(qa, input);
        warnUnchangedScenes(qa,input.path("episodeScript").path("scenes"));
        warnWeakHook(qa,input.path("episodeScript"));
        checkEpisodeEnding(qa,input);
        boolean hasDelta = DELTAS.stream().anyMatch(key -> !qa.path("episodeDelta").path(key).isEmpty());
        if (!hasDelta) block(qa, "NO_NARRATIVE_DELTA", "SCENE_PURPOSE", "本集没有事实、关系、目标、知识、风险或资源变化，删除后不影响后续剧情",
                "保留已确认的 startState/endState，在当前集加入由角色选择造成且会被后续继承的真实变化");

        JsonNode outline = input.path("episodeOutline");
        long repeated = java.util.stream.StreamSupport.stream(input.path("recentStructuralSummaries").spliterator(), false)
                .filter(previous -> samePattern(outline, previous)).count();
        if (repeated >= 3) block(qa, "REPEATED_EPISODE_PATTERN", "REDUNDANCY", "最近多集重复相同的开场、冲突、兑现和断章功能",
                "更换本集的冲突机制和兑现方式，并让新的选择改变关系、知识、资源或风险状态");

        JsonNode satisfactionContract=input.path("showrunnerContract").path("emotionContract").path("satisfactionContract");
        if(satisfactionContract.isObject()){
            SatisfactionEngine.Result satisfactionResult=satisfaction.evaluate(satisfactionContract,input.path("recentStructuralSummaries"));
            satisfactionResult.risks().forEach(risk->block(qa,"SATISFACTION_CONTRACT_"+risk.code(),"PAYOFF","爽感合同："+risk.message(),"按本剧 StoryType 的情绪合同修正压力、信息差、兑现推进或重复方式"));
        }
        JsonNode ledger=input.path("episodeScript").path("evidenceLedger");
        if(ledger.isArray()&&!ledger.isEmpty()){
            for(var risk:evidenceLedger.validate(ledger,input.path("episodeScript").path("storyFacts"),input.path("episodeScript").path("characterKnowledge"),input.path("evidenceWorld"),input.path("episodeNo").asLong()))
                block(qa,"EVIDENCE_LEDGER_"+risk.code(),"CONTINUITY","证据链："+risk.message(),"修正 Evidence、StoryFact、角色知情时间或证据持有人，使其不越过当前故事时间");
        }

        for(JsonNode issue:qa.path("issues"))if("BLOCKER".equals(issue.path("severity").asText())){
            String message=issue.path("message").asText(),recommendation=issue.path("recommendation").asText();
            if(!message.isBlank()&&!contains(qa.withArray("blockingIssues"),message))qa.withArray("blockingIssues").add(message);
            if(!recommendation.isBlank()&&!contains(qa.withArray("rewriteInstructions"),recommendation))qa.withArray("rewriteInstructions").add(recommendation);
        }

        qa.put("passed", qa.path("passed").asBoolean() && qa.path("blockingIssues").isEmpty());
        qa.put("rewriteRequired", !qa.path("passed").asBoolean() || qa.path("rewriteRequired").asBoolean());
        return qa;
    }

    private void warnWeakHook(ObjectNode qa,JsonNode script){
        if(!script.path("beatBoundaries").isArray()||script.path("beatBoundaries").isEmpty())return;
        HookRule.Result result=hookRule.evaluate(script);if(result.passed())return;
        qa.withArray("issues").add(Documents.obj().put("code",result.code()).put("severity","WARNING").put("category","HOOK")
            .put("message","前三秒没有同时建立明确的观看驱动力与可被观众感知的动作、对白或画面")
            .put("evidence",String.join(",",result.evidence()))
            .put("recommendation","为前三秒的 Beat 标注冲突、异常、危险、谜团、秘密、情绪、身份反差、问题或视觉意外，并用具体动作、对白或画面呈现")
            .put("targetPath","$.episodeScript.beatBoundaries"));
    }

    private void checkEpisodeEnding(ObjectNode qa,JsonNode input){
        JsonNode ending=input.path("episodeScript").path("episodeEnding"),policy=input.path("rulePack").path("storyTypeRule").path("episodeEndingPolicy");
        if(!ending.isObject()||!policy.isObject())return;
        EpisodeEndingRule.Result result=endingRule.evaluate(ending,policy);if(result.passed())return;
        String message="集尾没有达到本剧类型规则要求："+ending.path("description").asText();
        String recommendation="按当前 StoryType 的强度要求，用开放问题、未决冲突、揭示、决定、危险或反转留下具体的后续压力";
        if("HIGH".equals(policy.path("minimumStrength").asText()))block(qa,result.code(),"CLIFFHANGER",message,recommendation);
        else qa.withArray("issues").add(Documents.obj().put("code",result.code()).put("severity","WARNING").put("category","CLIFFHANGER")
            .put("message",message).put("evidence",String.join(",",result.evidence())).put("recommendation",recommendation)
            .put("targetPath","$.episodeScript.episodeEnding"));
    }

    private void warnUnchangedScenes(ObjectNode qa,JsonNode scenes){
        if(!scenes.isArray())return;int index=0;
        for(JsonNode scene:scenes){
            JsonNode start=scene.path("startState"),end=scene.path("endState");
            boolean formal=start.isObject()&&end.isObject(),explicitChange=List.of("informationChange","relationshipChange","emotionChange","characterStateChange")
                .stream().anyMatch(field->!scene.path(field).asText("").trim().isBlank());
            if(formal&&start.equals(end)&&!explicitChange){
                String label=scene.path("name").asText(scene.path("sceneId").asText("第 "+(index+1)+" 场"));
                qa.withArray("issues").add(Documents.obj().put("code","SCENE_NO_STATE_CHANGE").put("severity","WARNING").put("category","SCENE_PURPOSE")
                    .put("message",label+" 的目标、关系、信息、情绪和人物状态均未发生变化")
                    .put("evidence","startState 与 endState 相同，且没有声明任何变化")
                    .put("recommendation","让角色选择造成可被后续继承的信息、关系、情绪、目标或人物状态变化；否则删除或合并此场")
                    .put("targetPath","$.episodeScript.scenes["+index+"]"));
            }
            index++;
        }
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

    private void block(ObjectNode qa, String code, String category, String issue, String rewrite) {
        ArrayNode blocking = qa.withArray("blockingIssues");
        if (!contains(blocking, issue)) blocking.add(issue);
        ArrayNode issues=qa.withArray("issues");boolean recorded=false;for(JsonNode existing:issues)if(code.equals(existing.path("code").asText())){recorded=true;break;}
        if(!recorded)issues.add(Documents.obj().put("code",code).put("severity","BLOCKER").put("category",category).put("message",issue)
            .put("evidence",issue).put("recommendation",rewrite).put("targetPath","$.episodeScript"));
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
