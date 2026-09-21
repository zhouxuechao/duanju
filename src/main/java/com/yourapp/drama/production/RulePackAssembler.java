package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.*;

/** Combines independently selected domain packs without allowing cross-domain overriding. */
@Component
public final class RulePackAssembler {
    public record AssembledRulePack(List<RuntimeRulePackLoader.RuleFragment> rules, String content,
                                    Map<String, String> upstreamCommits, String fingerprint) {
        public AssembledRulePack {
            rules = List.copyOf(rules);
            upstreamCommits = Map.copyOf(upstreamCommits);
        }
    }

    public AssembledRulePack assemble(Collection<RuntimeRulePackLoader.RulePack> packs) {
        LinkedHashMap<String, RuntimeRulePackLoader.RuleFragment> unique = new LinkedHashMap<>();
        for (RuntimeRulePackLoader.RulePack pack : packs == null ? List.<RuntimeRulePackLoader.RulePack>of() : packs) {
            for (RuntimeRulePackLoader.RuleFragment rule : pack.rules())
                unique.putIfAbsent(rule.namespace() + ":" + rule.ruleId(), rule);
        }
        List<RuntimeRulePackLoader.RuleFragment> rules = List.copyOf(unique.values());
        Map<String, String> commits = new TreeMap<>();
        for (RuntimeRulePackLoader.RuleFragment rule : rules) commits.put(rule.sourceRepo(), rule.upstreamCommit());
        return new AssembledRulePack(rules, render(rules), commits, fingerprint(rules));
    }

    static String render(Collection<RuntimeRulePackLoader.RuleFragment> rules) {
        StringBuilder content = new StringBuilder();
        for (RuntimeRulePackLoader.RuleFragment rule : rules) {
            if (content.length() > 0) content.append("\n\n");
            content.append("## ").append(rule.namespace()).append(" / ").append(rule.ruleId()).append('\n')
                    .append(rule.content());
        }
        return content.toString();
    }

    private String fingerprint(List<RuntimeRulePackLoader.RuleFragment> rules) {
        String identity = rules.stream().map(rule -> rule.namespace() + ":" + rule.ruleId() + ":" + rule.contentHash())
                .reduce((a, b) -> a + "|" + b).orElse("");
        return RuntimeRulePackLoader.sha256(identity);
    }
}
