package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulePackBudgeterTest {
    @Test
    void removesExamplesAndLowPriorityPatternsBeforeContinuityRules() {
        var core = fragment("continuity", RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE, "C".repeat(120), 980);
        var type = fragment("satisfaction", RuntimeRulePackLoader.Namespace.STORY_TYPE, "T".repeat(80), 700);
        var example = fragment("example-case", RuntimeRulePackLoader.Namespace.STORY_PATTERN, "E".repeat(200), 100);
        var assembled = new RulePackAssembler().assemble(List.of(pack(core), pack(type), pack(example)));

        RulePackBudgeter.BudgetedRulePack budgeted = new RulePackBudgeter().fit(assembled, 280);

        assertThat(budgeted.included()).extracting(RuntimeRulePackLoader.RuleFragment::ruleId)
                .contains("continuity", "satisfaction").doesNotContain("example-case");
        assertThat(budgeted.droppedRuleIds()).containsExactly("example-case");
        assertThat(budgeted.content()).contains("C".repeat(20));
    }

    private RuntimeRulePackLoader.RuleFragment fragment(String id, RuntimeRulePackLoader.Namespace namespace, String content, int priority) {
        return new RuntimeRulePackLoader.RuleFragment(id, namespace, content,
                RuntimeRulePackLoader.sha256(content), priority, "repo", "commit", "path/" + id + ".md");
    }

    private RuntimeRulePackLoader.RulePack pack(RuntimeRulePackLoader.RuleFragment fragment) {
        return new RuntimeRulePackLoader.RulePack(fragment.namespace(), List.of(fragment.ruleId()), fragment.content(),
                fragment.upstreamCommit(), fragment.contentHash(), List.of(fragment));
    }
}
