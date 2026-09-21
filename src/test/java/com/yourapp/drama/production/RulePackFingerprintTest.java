package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulePackFingerprintTest {
    @Test
    void fingerprintIncludesPromptRulesProfilesCommitsAndCompilerVersion() {
        RuntimeRulePackLoader loader = new RuntimeRulePackLoader();
        RuntimeRulePackLoader.RulePack rules = loader.load(
                RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE,
                List.of("character-bible", "continuity-ledger"));
        RulePackFingerprint fingerprint = new RulePackFingerprint();
        RulePackFingerprint.Context base = new RulePackFingerprint.Context(
                "base prompt", List.of(rules), "SUSPENSE", "MICRO", "GENERAL",
                "DEEPSEEK_WRITER", Map.of("screenwriting", rules.upstreamCommit()), "story-compiler-v3");

        String first = fingerprint.compute(base);
        String changedPrompt = fingerprint.compute(new RulePackFingerprint.Context(
                "changed base prompt", List.of(rules), "SUSPENSE", "MICRO", "GENERAL",
                "DEEPSEEK_WRITER", Map.of("screenwriting", rules.upstreamCommit()), "story-compiler-v3"));
        String changedProfile = fingerprint.compute(new RulePackFingerprint.Context(
                "base prompt", List.of(rules), "SUSPENSE", "LONG", "GENERAL",
                "DEEPSEEK_WRITER", Map.of("screenwriting", rules.upstreamCommit()), "story-compiler-v3"));

        assertThat(first).hasSize(64).isNotEqualTo(changedPrompt).isNotEqualTo(changedProfile);
    }
}
