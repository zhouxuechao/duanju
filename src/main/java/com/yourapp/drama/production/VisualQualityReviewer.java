package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.JsonNode;
public interface VisualQualityReviewer { JsonNode review(JsonNode expectedContext,JsonNode generatedImage); }
