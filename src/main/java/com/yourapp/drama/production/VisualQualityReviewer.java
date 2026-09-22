package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.JsonNode;
public interface VisualQualityReviewer extends SemanticRule<JsonNode> {
    JsonNode review(JsonNode expectedContext,JsonNode generatedImage);
    @Override default JsonNode evaluate(JsonNode expectedContext,JsonNode generatedImage){return review(expectedContext,generatedImage);}
}
