package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulePackResolverTest {
    private final RulePackResolver resolver = new RulePackResolver(new RuntimeRulePackLoader());

    @Test
    void satisfactionRulesAreScopedToApplicableStoryTypes() {
        var revenge = resolver.story("OUTLINE_BATCH", "REVENGE", List.of("REBIRTH"));
        var suspense = resolver.story("OUTLINE_BATCH", "SUSPENSE", List.of("HIDDEN_IDENTITY"));

        assertThat(ruleIds(revenge)).contains("satisfaction-model");
        assertThat(ruleIds(suspense)).doesNotContain("satisfaction-model");
        assertThat(ruleIds(suspense)).contains("emotion-contract", "continuity-ledger");
    }

    @Test
    void topViewIsSelectedOnlyForComplexSpatialPlans() {
        assertThat(ruleIds(resolver.director("S0", "A1", "BASIC"))).doesNotContain("spatial-topview-camera", "facs");
        assertThat(ruleIds(resolver.director("S4", "A3", "FACS"))).contains("spatial-topview-camera", "facs", "asset-first-pipeline");
    }

    private List<String> ruleIds(List<RuntimeRulePackLoader.RulePack> packs) {
        return packs.stream().flatMap(pack -> pack.ruleIds().stream()).toList();
    }
}
