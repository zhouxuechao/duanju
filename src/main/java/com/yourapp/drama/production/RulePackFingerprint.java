package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.*;

/** Stable identity for the exact prompt knowledge used by a generation. */
@Component
public final class RulePackFingerprint {
    public record Context(String basePromptContent, List<RuntimeRulePackLoader.RulePack> selectedPacks,
                          String storyType, String episodeFormat, String distributionProfile,
                          String providerModelProfile, Map<String, String> upstreamCommits,
                          String promptCompilerVersion) {
        public Context {
            selectedPacks = List.copyOf(selectedPacks == null ? List.of() : selectedPacks);
            upstreamCommits = Map.copyOf(upstreamCommits == null ? Map.of() : upstreamCommits);
        }
    }

    public String compute(Context context) {
        StringBuilder identity = new StringBuilder();
        append(identity, "base", context.basePromptContent());
        context.selectedPacks().stream().flatMap(pack -> pack.rules().stream())
                .sorted(Comparator.comparing(rule -> rule.namespace().name() + ":" + rule.ruleId()))
                .forEach(rule -> append(identity, rule.namespace() + ":" + rule.ruleId(), rule.contentHash()));
        append(identity, "storyType", context.storyType());
        append(identity, "episodeFormat", context.episodeFormat());
        append(identity, "distribution", context.distributionProfile());
        append(identity, "modelProfile", context.providerModelProfile());
        context.upstreamCommits().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> append(identity, "commit:" + entry.getKey(), entry.getValue()));
        append(identity, "compiler", context.promptCompilerVersion());
        return RuntimeRulePackLoader.sha256(identity.toString());
    }

    private void append(StringBuilder target, String key, String value) {
        String safe = Objects.toString(value, "");
        target.append(key.length()).append(':').append(key).append('=').append(safe.length()).append(':').append(safe).append('|');
    }
}
