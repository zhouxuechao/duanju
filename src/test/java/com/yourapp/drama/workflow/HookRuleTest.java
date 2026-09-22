package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class HookRuleTest {
    private final HookRule rule = new HookRule();

    @Test void openingBeatNeedsBothAStructuredHookSignalAndSomethingTheAudienceCanObserve() {
        ObjectNode script=obj();
        script.putArray("beatBoundaries").add(obj().put("beatId","B1").put("startSec",0).put("endSec",3)
            .put("purpose","介绍人物日常").put("action","").put("dialogue","").put("visualInformation","")
            .set("hookSignals",script.arrayNode()));

        HookRule.Result result=rule.evaluate(script);

        assertThat(result.passed()).isFalse();
        assertThat(result.code()).isEqualTo("HOOK_NO_OBSERVABLE_TRIGGER");
    }

    @Test void ruleUsesStructuredMeaningAndPresentationInsteadOfKeywordMatching() {
        ObjectNode script=obj(),beat=obj().put("beatId","B1").put("startSec",0).put("endSec",2.8)
            .put("purpose","建立观看问题").put("action","人物打开只属于自己的储物柜")
            .put("dialogue","").put("visualInformation","柜内坐着一个与人物外貌完全相同的人");
        beat.putArray("hookSignals").add("IDENTITY_CONTRAST").add("QUESTION");script.putArray("beatBoundaries").add(beat);

        HookRule.Result result=rule.evaluate(script);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence()).contains("IDENTITY_CONTRAST","VISUAL");
    }
}
