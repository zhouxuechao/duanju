package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FakeVisualQualityReviewerTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void verifiesEachExpectedConstraintAndNamesOnlyActualFailures(){
        ObjectNode required=mapper.createObjectNode().put("characterIdentity","face-lock-1").put("clothing","black robe")
            .put("location","north wall").put("prop","bell in right hand").put("composition","medium shot").put("action","turn once").put("style","realistic");
        ObjectNode observed=required.deepCopy().put("clothing","white robe").put("composition","full body");
        ObjectNode expected=mapper.createObjectNode().set("requiredConstraints",required);
        ObjectNode image=mapper.createObjectNode().set("observedConstraints",observed);
        var result=new FakeVisualQualityReviewer(mapper).review(expected,image);
        assertThat(result.path("decision").asText()).isEqualTo("REGENERATE");
        assertThat(result.path("failureCodes").toString()).contains("CLOTHING_MISMATCH","COMPOSITION_ERROR").doesNotContain("LOCATION_MISMATCH","PROP_MISMATCH");
        assertThat(result.has("scores")).isFalse();
        assertThat(result.path("clothingConsistency").path("score").asInt()).isZero();
        assertThat(result.path("locationConsistency").path("score").asInt()).isEqualTo(100);
    }
    @Test void missingExpectedConstraintsCanNeverProduceAPass(){
        var result=new FakeVisualQualityReviewer(mapper).review(mapper.createObjectNode(),mapper.createObjectNode());
        assertThat(result.path("decision").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.path("failureOriginHint").asText()).isEqualTo("CONTEXT_RESOLUTION");
    }
}
