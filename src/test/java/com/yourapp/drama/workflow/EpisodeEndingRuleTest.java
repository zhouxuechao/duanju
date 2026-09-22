package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeEndingRuleTest {
    private final EpisodeEndingRule rule=new EpisodeEndingRule();

    @Test void strongTypePolicyRejectsANaturalCloseEvenWhenTheModelScoresItHighly(){
        var ending=obj().put("primaryType","NATURAL_CLOSE").put("strength","LOW")
            .put("description","人物回家睡觉").put("unresolvedPressure","").put("nextEpisodeQuestion","");

        var result=rule.evaluate(ending,obj().put("minimumStrength","HIGH"));

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo("EPISODE_ENDING_TOO_WEAK");
    }

    @Test void lowIntensityFormatCanUseAQuietButStillOpenQuestion(){
        var ending=obj().put("primaryType","OPEN_QUESTION").put("strength","LOW")
            .put("description","人物看见未署名的回信").put("unresolvedPressure","来信者身份未知")
            .put("nextEpisodeQuestion","谁寄出了回信");

        assertThat(rule.evaluate(ending,obj().put("minimumStrength","LOW")).passed()).isTrue();
    }
}
