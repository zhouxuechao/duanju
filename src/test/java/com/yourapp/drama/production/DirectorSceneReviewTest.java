package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DirectorSceneReviewTest {
    private final ObjectMapper mapper=new ObjectMapper();private final DirectorSceneReview review=new DirectorSceneReview(mapper);
    @Test void finalSceneReviewFindsRepeatedCompositionAndFlatRhythm(){
        ObjectNode scene=base();for(int i=0;i<8;i++)scene.withArray("shots").add(shot("MEDIUM",3,"actor","A_SIDE"));
        ObjectNode result=review.review(scene);assertThat(result.path("passed").asBoolean()).isFalse();assertThat(result.path("failureCodes")).extracting(v->v.asText()).contains("MECHANICAL_SHOT_SIZE","FLAT_PACING","DUPLICATE_COMPOSITION_RUN");
    }
    @Test void finalSceneReviewReportsVarietyReactionAndStableSpace(){
        ObjectNode scene=base();String[] sizes={"WIDE","MEDIUM","OVER_THE_SHOULDER","CLOSE_UP","INSERT","MEDIUM_CLOSE_UP"};double[] durations={3.5,3,2.5,2,2.25,3};
        for(int i=0;i<sizes.length;i++){ObjectNode shot=shot(sizes[i],durations[i],i==3?"listener":"actor","A_SIDE");if(i==3)shot.put("directorIntent","SHOW_REACTION");scene.withArray("shots").add(shot);}
        ObjectNode result=review.review(scene);assertThat(result.path("passed").asBoolean()).isTrue();assertThat(result.path("uniqueShotSizes").asInt()).isEqualTo(6);assertThat(result.path("reactionShots").asInt()).isEqualTo(1);assertThat(result.path("spatialContinuityBreaks").asInt()).isZero();
    }
    private ObjectNode base(){ObjectNode root=mapper.createObjectNode();ObjectNode p=root.putObject("directorPlan").put("shotRepetitionReason","").put("scenePacing","NORMAL");p.putObject("styleProfile").put("cameraActivity","LOW").put("reactionShotPreference",0);root.putArray("dramaticBeats");root.putArray("shots");return root;}
    private ObjectNode shot(String size,double duration,String subject,String side){ObjectNode s=mapper.createObjectNode().put("shotSize",size).put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("duration",duration).put("subject",subject).put("directorIntent","SHOW_ACTION").put("beatId","b");s.putObject("blocking").put("axis","axis").put("axisSide",side);return s;}
}
