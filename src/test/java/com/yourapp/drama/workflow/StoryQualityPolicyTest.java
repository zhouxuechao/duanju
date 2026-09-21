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
    }

    @Test void fourStructurallyIdenticalEpisodesAreBlockedWithoutLoadingFullHistory() {
        ObjectNode qa = qa();
        qa.withObject("episodeDelta").withArray("knowledgeChanged").add("获得新线索");
        ObjectNode checked = policy.apply(qa, input(4));
        assertThat(checked.path("passed").asBoolean()).isFalse();
        assertThat(checked.path("blockingIssues").toString()).contains("重复相同");
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
