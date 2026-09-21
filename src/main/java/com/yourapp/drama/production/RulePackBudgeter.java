package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.*;

/** Drops examples and low relevance supplements before any canonical continuity rule. */
@Component
public final class RulePackBudgeter {
    public record BudgetedRulePack(List<RuntimeRulePackLoader.RuleFragment> included, List<String> droppedRuleIds,
                                   String content, int requestedBudgetChars, boolean coreExceededBudget) {
        public BudgetedRulePack {
            included = List.copyOf(included);
            droppedRuleIds = List.copyOf(droppedRuleIds);
        }
    }

    public BudgetedRulePack fit(RulePackAssembler.AssembledRulePack assembled, int budgetChars) {
        if (budgetChars < 1) throw new IllegalArgumentException("RULE_PACK_BUDGET_INVALID");
        List<RuntimeRulePackLoader.RuleFragment> kept = new ArrayList<>(assembled.rules());
        List<String> dropped = new ArrayList<>();
        List<RuntimeRulePackLoader.RuleFragment> candidates = kept.stream()
                .filter(rule -> rule.priority() < 900)
                .sorted(Comparator.comparingInt(this::dropRank).thenComparingInt(RuntimeRulePackLoader.RuleFragment::priority))
                .toList();
        for (RuntimeRulePackLoader.RuleFragment candidate : candidates) {
            if (RulePackAssembler.render(kept).length() <= budgetChars) break;
            kept.remove(candidate);
            dropped.add(candidate.ruleId());
        }
        String content = RulePackAssembler.render(kept);
        return new BudgetedRulePack(kept, dropped, content, budgetChars, content.length() > budgetChars);
    }

    private int dropRank(RuntimeRulePackLoader.RuleFragment rule) {
        if (rule.namespace() == RuntimeRulePackLoader.Namespace.STORY_PATTERN) return 0;
        if (rule.namespace() == RuntimeRulePackLoader.Namespace.PERFORMANCE) return 1;
        return 2;
    }
}
