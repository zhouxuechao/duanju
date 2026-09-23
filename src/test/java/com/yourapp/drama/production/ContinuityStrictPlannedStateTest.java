package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuityStrictPlannedStateTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ContinuityCompatibilityEvaluator evaluator=new ContinuityCompatibilityEvaluator();

    @Test void plannedCharacterIdentityAndLookCannotDisappear() {
        ObjectNode previous=state(),current=state();
        current.path("characters").path("actor").fields().forEachRemaining(ignored->{ });
        ((ObjectNode)current.path("characters").path("actor")).remove("identityId");
        ((ObjectNode)current.path("characters").path("actor")).remove("lookId");
        assertThat(evaluator.comparePlanned(previous,current)).extracting(ProductionModels.Risk::code)
                .contains("CONTINUITY_FIELD_MISSING");
    }

    @Test void plannedPropHolderCannotDisappear() {
        ObjectNode previous=state(),current=state();
        ((ObjectNode)current.path("props").path("bell")).remove("holder");
        assertThat(evaluator.comparePlanned(previous,current)).extracting(ProductionModels.Risk::code)
                .contains("CONTINUITY_FIELD_MISSING");
    }

    @Test void observedPartialEvidenceIsReportedWithoutPretendingItPassed() {
        ObjectNode take=mapper.createObjectNode().put("selected",true).put("locked",true).put("qcStatus","PASSED");
        ObjectNode observed=mapper.createObjectNode();
        observed.putObject("characters").putObject("actor");
        take.set("observedState",observed);
        assertThat(evaluator.compareObserved(take,state())).extracting(ProductionModels.Risk::code)
                .contains("INSUFFICIENT_EVIDENCE");
    }

    private ObjectNode state() {
        ObjectNode state=mapper.createObjectNode().put("locationId","yard");
        state.putObject("characters").putObject("actor").put("identityId","actor-identity").put("lookId","look-1").put("pose","standing");
        state.putObject("props").putObject("bell").put("holder","actor");
        return state;
    }
}
