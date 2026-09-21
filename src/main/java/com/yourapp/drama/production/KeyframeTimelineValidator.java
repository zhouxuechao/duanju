package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class KeyframeTimelineValidator {
    public void validate(JsonNode keyframes,double duration,VideoModelProfile profile){
        if(keyframes==null||!keyframes.isArray()||keyframes.isEmpty())throw new IllegalArgumentException("KEYFRAME_TIMELINE_EMPTY");
        double previousTime=-1;int previousState=Integer.MIN_VALUE;Set<KeyframeSemanticRole> unique=new HashSet<>();
        for(JsonNode frame:keyframes){KeyframeSemanticRole role=KeyframeSemanticRole.from(frame.path("semanticRole").asText());double time=frame.path("timeSec").asDouble(role==KeyframeSemanticRole.END_FRAME?duration:0);int state=frame.path("stateVersion").asInt(0);
            if(time<0||time>duration||time<previousTime)throw new IllegalArgumentException("KEYFRAME_TIME_OUT_OF_RANGE: "+time);
            if(state<previousState)throw new IllegalArgumentException("KEYFRAME_STATE_CAUSALITY_VIOLATION: stateVersion "+state+" follows "+previousState);
            if((role==KeyframeSemanticRole.START_FRAME||role==KeyframeSemanticRole.END_FRAME)&&!unique.add(role))throw new IllegalArgumentException("KEYFRAME_BOUNDARY_DUPLICATED: "+role);
            if(role==KeyframeSemanticRole.START_FRAME&&time!=0)throw new IllegalArgumentException("KEYFRAME_START_TIME_INVALID");
            if(role==KeyframeSemanticRole.END_FRAME&&Double.compare(time,duration)!=0)throw new IllegalArgumentException("KEYFRAME_END_TIME_INVALID");
            if(role==KeyframeSemanticRole.INTERMEDIATE_KEYFRAME&&(time<=0||time>=duration))throw new IllegalArgumentException("KEYFRAME_INTERMEDIATE_TIME_INVALID");
            if(role==KeyframeSemanticRole.END_FRAME&&!profile.supportsEndFrame())throw new IllegalArgumentException("KEYFRAME_END_FRAME_UNSUPPORTED");
            if(!frame.hasNonNull("providerUrl")||frame.path("providerUrl").asText().isBlank())throw new IllegalArgumentException("KEYFRAME_PROVIDER_URL_MISSING");
            previousTime=time;previousState=state;
        }
    }
}
