package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.List;

/** Reuses the canonical continuity planner instead of maintaining a second set of fact rules. */
@Component
public final class ContinuityDeterministicRule implements DeterministicRule {
    private final ContinuityEngine continuity;
    public ContinuityDeterministicRule(ContinuityEngine continuity){this.continuity=continuity;}
    @Override public String id(){return "CONTINUITY";}
    @Override public List<ProductionModels.Risk> evaluate(JsonNode generationContext){return continuity.plan(generationContext).risks();}
}
