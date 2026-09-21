package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class LocationTopologySchemaTest {
    @Test void coreRequiresAWorldCoordinateTopologyForEveryLocation(){
        JsonNode bible=StoryDevelopmentSchemas.core().at("/properties/locations/items/properties/locationBible");
        assertThat(required(bible)).contains("coordinateSystem","dimensions","surfaces","fixedFeatures","spatialRelations","lightSources","visualInvariants","prohibitedElements");
        assertThat(required(bible.at("/properties/fixedFeatures/items"))).contains("featureId","supportSurfaceId","worldPosition","size","state","appearance");
        assertThat(required(bible.at("/properties/spatialRelations/items"))).contains("subjectId","relation","objectId","distance");
    }

    private java.util.List<String> required(JsonNode schema){
        return StreamSupport.stream(schema.path("required").spliterator(),false).map(JsonNode::asText).toList();
    }
}
