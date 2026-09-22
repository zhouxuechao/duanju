package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;

/** A subjective assessment that may use a model only after deterministic rules pass. */
public interface SemanticRule<E> {
    JsonNode evaluate(JsonNode expectedContext,E evidence);
}
