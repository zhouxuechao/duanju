package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public class FakeVideoQualityReviewer implements VideoQualityReviewer {
    private final ObjectMapper mapper;
    public FakeVideoQualityReviewer(ObjectMapper mapper){this.mapper=mapper;}
    @Override public JsonNode review(JsonNode expected,List<Frame> frames){
        ObjectNode observed=mapper.createObjectNode();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());return new FakeVisualQualityReviewer(mapper).review(expected,observed);
    }
}
