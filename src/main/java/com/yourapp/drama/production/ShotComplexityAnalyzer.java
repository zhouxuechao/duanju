package com.yourapp.drama.production;

import java.util.ArrayList;
import java.util.List;

/** Scores provider-independent shot feasibility before any paid generation. */
public final class ShotComplexityAnalyzer {
    public enum Decision { KEEP, SPLIT_RECOMMENDED, BLOCK_TOO_COMPLEX }
    public record Input(int actorCount,int temporalEventCount,int propInteractionCount,int spatialTransitionCount,
                        int cameraMoveCount,int stateChangeCount,int dialogueCount,int referenceComplexity) {}
    public record Result(Decision decision,int score,List<String> reasons) {}
    public Result analyze(Input in){
        int score=in.actorCount()+in.temporalEventCount()*2+in.propInteractionCount()*2+in.spatialTransitionCount()*2+
                in.cameraMoveCount()+in.stateChangeCount()*2+in.dialogueCount()+in.referenceComplexity();
        List<String> reasons=new ArrayList<>();
        if(in.actorCount()>4)reasons.add("too many actors");if(in.temporalEventCount()>4)reasons.add("too many sequential events");
        if(in.propInteractionCount()>2)reasons.add("too many prop interactions");if(in.spatialTransitionCount()>2)reasons.add("too many spatial transitions");
        Decision decision=score>=28||in.temporalEventCount()>5||in.actorCount()>6?Decision.BLOCK_TOO_COMPLEX:score>=12?Decision.SPLIT_RECOMMENDED:Decision.KEEP;
        return new Result(decision,score,List.copyOf(reasons));
    }
}
