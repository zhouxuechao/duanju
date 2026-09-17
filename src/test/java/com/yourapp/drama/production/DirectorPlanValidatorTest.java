package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DirectorPlanValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final DirectorPlanValidator validator=new DirectorPlanValidator();

    @Test void rejectsMechanicalShotSizesAndFlatTiming(){
        ObjectNode plan=scene("NORMAL","BALANCED");for(int i=0;i<8;i++)shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("MECHANICAL_SHOT_SIZE","FLAT_PACING");
    }
    @Test void rejectsCameraMovementThatContradictsTheStyleProfile(){
        ObjectNode plan=scene("NORMAL","LOW");for(int i=0;i<8;i++)shot(plan,i%2==0?"WIDE":"CLOSE_UP","PAN",2+i%3,"SHOW_ACTION","beat-1");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("EXCESSIVE_CAMERA_ACTIVITY");
    }
    @Test void emotionArcMustConnectAcrossBeats(){
        ObjectNode plan=scene("TENSION_BUILD","LOW");ObjectNode b2=beat("beat-2","恐惧","崩溃");plan.withArray("dramaticBeats").add(b2);
        shot(plan,"WIDE","STATIC",3,"ESTABLISH_SPACE","beat-1");shot(plan,"CLOSE_UP","STATIC",3,"SHOW_REACTION","beat-2");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("EMOTION_ARC_GAP");
    }
    @Test void differentActiveCharactersMayCarryIndependentEmotionTracks(){
        ObjectNode plan=scene("TENSION_BUILD","LOW");ObjectNode b2=beat("beat-2","放松等待","突然惊恐");
        b2.withArray("activeCharacters").removeAll().add("listener");plan.withArray("dramaticBeats").add(b2);
        shot(plan,"WIDE","STATIC",3,"ESTABLISH_SPACE","beat-1");shot(plan,"CLOSE_UP","STATIC",3,"SHOW_REACTION","beat-2");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).doesNotContain("EMOTION_ARC_GAP");
    }
    @Test void importantMultiCharacterRevealNeedsAReactionShot(){
        ObjectNode plan=scene("TENSION_BUILD","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);
        beat.withArray("activeCharacters").add("listener");beat.put("informationReveal","父亲并非意外死亡").put("importance","HIGH");
        shot(plan,"MEDIUM","STATIC",3,"REVEAL_INFORMATION","beat-1");shot(plan,"CLOSE_UP","STATIC",3,"REVEAL_INFORMATION","beat-1");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("REACTION_COVERAGE_MISSING");
    }
    @Test void revealShotCannotRelabelItselfAsTheOnlyReactionCoverage(){
        ObjectNode plan=scene("TENSION_BUILD","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);
        beat.withArray("activeCharacters").add("listener");beat.put("informationReveal","门外的人已经死亡").put("importance","CLIMAX");
        shot(plan,"CLOSE_UP","STATIC",3,"SHOW_REACTION","beat-1");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("REACTION_COVERAGE_MISSING");
    }
    @Test void anOffscreenPayoffMayResolveInsideOneDedicatedReactionComposition(){
        ObjectNode plan=scene("TENSION_BUILD","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);
        beat.withArray("activeCharacters").add("listener");beat.put("informationReveal","门外响起死者使用的铜铃声").put("importance","CLIMAX");
        shot(plan,"MEDIUM_FULL","STATIC",3,"EMPHASIZE_EMOTION","beat-1");ObjectNode reaction=(ObjectNode)plan.path("shots").path(0);reaction.put("relationToPrevious","CONTINUOUS");reaction.putArray("characterIds").add("speaker").add("listener");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).doesNotContain("REACTION_COVERAGE_MISSING");
    }
    private ObjectNode scene(String pacing,String activity){
        ObjectNode root=mapper.createObjectNode();ObjectNode director=root.putObject("directorPlan").put("scenePacing",pacing).put("shotRepetitionReason","").put("cameraStrategy","稳定机位服务剧情信息");
        director.putObject("styleProfile").put("cameraActivity",activity).put("reactionShotPreference",0.7);
        root.putArray("dramaticBeats").add(beat("beat-1","平静","怀疑"));root.putArray("shots");return root;
    }
    private ObjectNode beat(String id,String before,String after){
        ObjectNode beat=mapper.createObjectNode().put("beatId",id).put("emotionBefore",before).put("emotionAfter",after).put("informationReveal","").put("importance","MEDIUM");beat.putArray("activeCharacters").add("speaker");return beat;
    }
    private void shot(ObjectNode root,String size,String movement,double duration,String intent,String beat){root.withArray("shots").add(mapper.createObjectNode().put("shotSize",size).put("cameraMovement",movement).put("duration",duration).put("directorIntent",intent).put("beatId",beat));}
}
