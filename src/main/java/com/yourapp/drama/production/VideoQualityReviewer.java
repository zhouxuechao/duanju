package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface VideoQualityReviewer {
    JsonNode review(JsonNode expectedContext,List<Frame> frames);
    record Frame(double timestampSeconds,String imageDataUrl){}
}
