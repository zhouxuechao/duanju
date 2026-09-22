package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface VideoQualityReviewer extends SemanticRule<List<VideoQualityReviewer.Frame>> {
    JsonNode review(JsonNode expectedContext,List<Frame> frames);
    @Override default JsonNode evaluate(JsonNode expectedContext,List<Frame> frames){return review(expectedContext,frames);}
    record Frame(double timestampSeconds,String imageDataUrl){}
}
