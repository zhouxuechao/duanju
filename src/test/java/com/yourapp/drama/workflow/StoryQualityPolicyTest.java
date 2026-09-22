package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class StoryQualityPolicyTest {
    private final StoryQualityPolicy policy = new StoryQualityPolicy();

    @Test void emptyEpisodeDeltaCannotPassEvenWhenTheModelSaysItPassed() {
        ObjectNode qa = qa();
        ObjectNode checked = policy.apply(qa, input(0));
        assertThat(checked.path("passed").asBoolean()).isFalse();
        assertThat(checked.path("rewriteRequired").asBoolean()).isTrue();
        assertThat(checked.path("blockingIssues").get(0).asText()).contains("没有事实");
        assertThat(checked.path("issues").get(0).path("severity").asText()).isEqualTo("BLOCKER");
        assertThat(checked.path("issues").get(0).path("category").asText()).isEqualTo("SCENE_PURPOSE");
        assertThat(checked.path("issues").get(0).path("code").asText()).isEqualTo("NO_NARRATIVE_DELTA");
        assertThat(checked.path("issues").get(0).path("recommendation").asText()).isNotBlank();
    }

    @Test void confirmedBoundaryChangeRecoversDeltaWhenTheModelReturnsEmptyArrays() {
        ObjectNode qa = qa();
        ObjectNode input = input(0);
        input.withObject("episodeOutline").put("startState", "身份稳定、票未翻面、灯亮")
                .put("endState", "身份动摇、票已翻面、灯灭");
        input.withObject("episodeOutline").putArray("progressionEvents")
                .add(obj().put("atSec", 8).put("type", "身份/知识").put("description", "主角看见自己的注销记录"))
                .add(obj().put("atSec", 20).put("type", "风险/目标").put("description", "身份风险由猜测变成眼前证据"));
        input.set("episodeScript", obj().put("startState", "身份稳定、票未翻面、灯亮")
                .put("endState", "身份动摇、票已翻面、灯灭"));

        ObjectNode checked = policy.apply(qa, input);

        assertThat(checked.path("passed").asBoolean()).isTrue();
        assertThat(checked.at("/episodeDelta/knowledgeChanged").get(0).asText()).contains("注销记录");
        assertThat(checked.at("/episodeDelta/riskChanged").get(0).asText()).contains("身份风险");
    }

    @Test void fourStructurallyIdenticalEpisodesAreBlockedWithoutLoadingFullHistory() {
        ObjectNode qa = qa();
        qa.withObject("episodeDelta").withArray("knowledgeChanged").add("获得新线索");
        ObjectNode checked = policy.apply(qa, input(4));
        assertThat(checked.path("passed").asBoolean()).isFalse();
        assertThat(checked.path("blockingIssues").toString()).contains("重复相同");
    }
    @Test void structuredScriptDoctorBlockerCannotBeOverriddenByAModelPassFlag(){
        ObjectNode qa=qa();qa.withObject("episodeDelta").withArray("knowledgeChanged").add("获得新线索");
        qa.withArray("issues").add(obj().put("code","FUTURE_REVEAL").put("severity","BLOCKER").put("category","REVEAL")
            .put("message","角色说出尚未获知的真相").put("evidence","第二场对白").put("recommendation","删除提前泄漏的真相").put("targetPath","$.scenes[1].dialogues[0]"));
        ObjectNode checked=policy.apply(qa,input(0));
        assertThat(checked.path("passed").asBoolean()).isFalse();
        assertThat(checked.path("blockingIssues").toString()).contains("尚未获知");
        assertThat(checked.path("rewriteInstructions").toString()).contains("删除提前泄漏");
    }

    @Test void sceneWithoutAnyStateChangeProducesStructuredDoctorWarning(){
        ObjectNode qa=qa();qa.withObject("episodeDelta").withArray("knowledgeChanged").add("本集其他场景获得新线索");
        ObjectNode input=input(0),script=obj();ObjectNode unchanged=obj().put("weather","rain").put("goal","wait");
        ObjectNode scene=obj().put("sceneId","scene-idle").put("sceneGoal","等待消息").put("conflict","无人回应")
            .put("dramaticFunction","停顿").put("informationChange","").put("relationshipChange","")
            .put("emotionChange","").put("characterStateChange","");
        scene.set("startState",unchanged);scene.set("endState",unchanged.deepCopy());script.putArray("scenes").add(scene);input.set("episodeScript",script);

        ObjectNode checked=policy.apply(qa,input);

        assertThat(checked.path("passed").asBoolean()).isTrue();
        assertThat(checked.path("issues")).anyMatch(issue->"SCENE_NO_STATE_CHANGE".equals(issue.path("code").asText())
            &&"WARNING".equals(issue.path("severity").asText())&&"$.episodeScript.scenes[0]".equals(issue.path("targetPath").asText()));
    }

    @Test void weakFirstThreeSecondsCannotBeHiddenByAModelHookScore(){
        ObjectNode qa=qa();qa.withObject("episodeDelta").withArray("knowledgeChanged").add("稍后获得线索");
        ObjectNode input=input(0),script=obj(),beat=obj().put("beatId","B1").put("startSec",0).put("endSec",3)
            .put("purpose","介绍日常").put("action","").put("dialogue","").put("visualInformation","");
        beat.putArray("hookSignals");script.putArray("beatBoundaries").add(beat);input.set("episodeScript",script);

        ObjectNode checked=policy.apply(qa,input);

        assertThat(checked.path("issues")).anyMatch(issue->"HOOK_NO_OBSERVABLE_TRIGGER".equals(issue.path("code").asText())
            &&"HOOK".equals(issue.path("category").asText())&&"WARNING".equals(issue.path("severity").asText()));
    }

    @Test void strongTypePolicyBlocksANaturalEndingDespiteAHighModelScore(){
        ObjectNode qa=qa();qa.withObject("episodeDelta").withArray("knowledgeChanged").add("获得线索");
        ObjectNode input=input(0),script=obj();script.set("episodeEnding",obj().put("primaryType","NATURAL_CLOSE").put("strength","LOW")
            .put("description","人物回家睡觉").put("unresolvedPressure","").put("nextEpisodeQuestion",""));input.set("episodeScript",script);
        input.set("rulePack",obj().set("storyTypeRule",obj().set("episodeEndingPolicy",obj().put("minimumStrength","HIGH"))));

        ObjectNode checked=policy.apply(qa,input);

        assertThat(checked.path("passed").asBoolean()).isFalse();
        assertThat(checked.path("issues")).anyMatch(issue->"EPISODE_ENDING_TOO_WEAK".equals(issue.path("code").asText())
            &&"CLIFFHANGER".equals(issue.path("category").asText())&&"BLOCKER".equals(issue.path("severity").asText()));
    }

    @Test void aDeclaredSatisfactionContractWorksForANewStoryTypeWithoutAJavaAllowlist(){
        ObjectNode qa=qa();qa.withObject("episodeDelta").withArray("knowledgeChanged").add("获得新信息");
        ObjectNode input=input(0);input.set("storyProfile",obj().put("storyType","OVERSEAS_SOCIAL_THRILLER"));
        ObjectNode contract=obj().put("negativeEmotion","被制度忽视").put("informationGap","").put("payoff","公开纠错").put("payoffDelayEpisodes",2);
        contract.putArray("amplifiers").add("公开听证");
        input.set("showrunnerContract",obj().set("emotionContract",obj().set("satisfactionContract",contract)));

        ObjectNode checked=policy.apply(qa,input);

        assertThat(checked.path("issues")).anyMatch(issue->"SATISFACTION_CONTRACT_INFORMATION_GAP_MISSING".equals(issue.path("code").asText()));
        assertThat(checked.path("passed").asBoolean()).isFalse();
    }

    private ObjectNode qa() {
        ObjectNode value=obj().put("passed",true).put("rewriteRequired",false);ObjectNode dimensions=value.putObject("dimensions");
        for(String key:List.of("hook","progression","conflict","characterConsistency","genreFit","continuity","payoff","cliffhanger","narrativeNecessity","dialogueNaturalness"))dimensions.put(key,85);
        ObjectNode delta=value.putObject("episodeDelta");for(String key:List.of("factsChanged","relationshipsChanged","goalsChanged","knowledgeChanged","riskChanged","resourcesChanged"))delta.putArray(key);
        value.putArray("blockingIssues");value.putArray("issues");value.putArray("rewriteInstructions");return value;
    }

    private ObjectNode input(int previousCount) {
        ObjectNode input=obj();ObjectNode outline=obj().put("episodeFunction","调查证据").put("hook","发现泥迹").put("mainConflict","有人擦除泥迹").put("payoff","确认来源").put("cliffhanger","脚步突然停止");input.set("episodeOutline",outline);
        var recent=input.putArray("recentStructuralSummaries");for(int i=0;i<previousCount;i++)recent.add(outline.deepCopy().put("episodeNo",i+1));return input;
    }
}
