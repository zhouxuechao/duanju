package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyframeTimelineValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final KeyframeTimelineValidator validator=new KeyframeTimelineValidator();
    private final VideoModelProfile profile=new ProviderCapabilityRegistry().profile("doubao-seedance-2-5-260628");

    @Test void acceptsCausallyOrderedStartIntermediateAndEndFrames() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME",0,1)).add(frame("INTERMEDIATE_KEYFRAME",2.5,2)).add(frame("END_FRAME",5,3));
        validator.validate(frames,5,profile);
    }

    @Test void rejectsFutureStateInjectedIntoEarlierKeyframe() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME",0,3)).add(frame("INTERMEDIATE_KEYFRAME",2,2)).add(frame("END_FRAME",5,4));
        assertThatThrownBy(()->validator.validate(frames,5,profile)).hasMessageContaining("KEYFRAME_STATE_CAUSALITY_VIOLATION");
    }

    @Test void rejectsEndFrameOutsideShotDuration() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME",0,1)).add(frame("END_FRAME",6,2));
        assertThatThrownBy(()->validator.validate(frames,5,profile)).hasMessageContaining("KEYFRAME_TIME_OUT_OF_RANGE");
    }

    @Test void rejectsEndFrameThatDoesNotDescribeTheShotBoundary() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME",0,1)).add(frame("END_FRAME",4,2));
        assertThatThrownBy(()->validator.validate(frames,5,profile)).hasMessageContaining("KEYFRAME_END_TIME_INVALID");
    }
    @Test void rejectsIntermediateKeyframePlacedOnABoundary() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME",0,1)).add(frame("INTERMEDIATE_KEYFRAME",0,2));
        assertThatThrownBy(()->validator.validate(frames,5,profile)).hasMessageContaining("KEYFRAME_INTERMEDIATE_TIME_INVALID");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode frame(String role,double time,int state){return mapper.createObjectNode().put("semanticRole",role).put("timeSec",time).put("stateVersion",state).put("providerUrl","https://media.example.com/"+role+".png");}
}
