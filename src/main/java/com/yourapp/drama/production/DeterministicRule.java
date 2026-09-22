package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** A rule whose answer is fully determined by persisted production facts. */
public interface DeterministicRule {
    String id();
    List<ProductionModels.Risk> evaluate(JsonNode generationContext);
}
