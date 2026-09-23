package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionBoundaryDeterministicRuleTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ProductionBoundaryDeterministicRule rule=new ProductionBoundaryDeterministicRule(new ContinuityCompatibilityEvaluator());

    @Test void shotLongerThanProviderCapabilityIsRejectedBeforeSemanticReview(){
        ObjectNode context=mapper.createObjectNode();context.putObject("shot").put("duration",13).put("relationToPrevious","ESTABLISHING");
        context.putObject("providerCapabilities").putArray("supportedDurations").add(4).add(8).add(12);

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code).containsExactly("PROVIDER_DURATION_EXCEEDED");
    }
    @Test void continuousShotRequiresAnAcceptedPreviousTake(){
        ObjectNode context=mapper.createObjectNode().put("providerMediaType","VIDEO"),shot=context.putObject("shot");shot.put("duration",4).put("relationToPrevious","CONTINUOUS");
        ObjectNode start=shot.putObject("startState").put("locationId","yard");
        context.putObject("previousTake").put("selected",true).put("locked",false).put("qcPassed",true).set("observedState",start.deepCopy());

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code).containsExactly("PREVIOUS_TAKE_NOT_ACCEPTED");
    }
    @Test void acceptedPreviousTakeWithoutObservedStateFailsClosed(){
        ObjectNode context=mapper.createObjectNode().put("providerMediaType","VIDEO"),shot=context.putObject("shot");shot.put("duration",4).put("relationToPrevious","CONTINUOUS");
        shot.putObject("startState").put("locationId","yard");
        context.putObject("previousTake").put("selected",true).put("locked",true).put("qcPassed",true);

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code).contains("PREVIOUS_STATE_MISSING");
    }
    @Test void acceptedEndMustMatchTheNextStartIncludingScreenDirection(){
        ObjectNode context=mapper.createObjectNode().put("providerMediaType","VIDEO"),shot=context.putObject("shot");shot.put("duration",4).put("relationToPrevious","CONTINUOUS");
        ObjectNode start=shot.putObject("startState").put("locationId","yard");start.putObject("characters").putObject("actor").put("screenDirection","FRAME_LEFT");
        ObjectNode previous=context.putObject("previousTake").put("selected",true).put("locked",true).put("qcPassed",true);
        ObjectNode observed=previous.putObject("observedState").put("locationId","hall");observed.putObject("characters").putObject("actor").put("screenDirection","FRAME_RIGHT");

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code)
            .containsExactly("LOCATION_DISCONTINUITY","SCREEN_DIRECTION_DISCONTINUITY");
    }
    @Test void unexplainedAxisCrossAndContinuousScreenDirectionReversalAreRejected(){
        ObjectNode context=mapper.createObjectNode().put("providerMediaType","VIDEO"),shot=context.putObject("shot");shot.put("duration",4).put("relationToPrevious","CONTINUOUS");
        ObjectNode currentBlocking=shot.putObject("blocking").put("axis","door-axis").put("axisSide","B_SIDE").put("axisChangeReason","");
        currentBlocking.putArray("characters").addObject().put("characterId","actor").put("screenDirection","FRAME_LEFT");
        ObjectNode previousBlocking=context.putObject("previousShot").putObject("blocking").put("axis","door-axis").put("axisSide","A_SIDE");
        previousBlocking.putArray("characters").addObject().put("characterId","actor").put("screenDirection","FRAME_RIGHT");
        ObjectNode start=shot.putObject("startState").put("locationId","yard");
        context.putObject("previousTake").put("selected",true).put("locked",true).put("qcPassed",true).set("observedState",start.deepCopy());

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code)
            .containsExactly("AXIS_SIDE_DISCONTINUITY","SCREEN_DIRECTION_DISCONTINUITY");
    }
    @Test void continuousKeyframeUsesPlannedContinuityWithoutRequiringAFutureVideoTake(){
        ObjectNode context=mapper.createObjectNode().put("providerMediaType","IMAGE"),shot=context.putObject("shot");shot.put("duration",4).put("relationToPrevious","CONTINUOUS");
        shot.putObject("startState").put("locationId","yard");

        assertThat(rule.evaluate(context)).extracting(ProductionModels.Risk::code).doesNotContain("PREVIOUS_TAKE_NOT_ACCEPTED");
    }
}
