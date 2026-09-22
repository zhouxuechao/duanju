package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;

/** Runs cost-free fact rules before any subjective model assessment. */
@Service
public class RuleEngine {
    private final List<DeterministicRule> deterministicRules;
    public RuleEngine(List<DeterministicRule> deterministicRules){this.deterministicRules=List.copyOf(deterministicRules);}

    public void requireDeterministicPass(JsonNode generationContext){
        List<ProductionModels.Risk> failures=new ArrayList<>();
        for(DeterministicRule rule:deterministicRules)for(ProductionModels.Risk risk:rule.evaluate(generationContext))
            if("ERROR".equals(risk.severity()))failures.add(new ProductionModels.Risk(risk.code(),risk.severity(),rule.id()+":"+risk.path(),risk.message()));
        if(!failures.isEmpty())throw new DeterministicRuleViolationException(failures);
    }
}
