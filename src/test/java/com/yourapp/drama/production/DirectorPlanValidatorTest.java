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
    @Test void explicitListenerPriorityRequiresAReactionForEverySensitiveEventType(){
        for(String eventType:java.util.List.of("REVEAL","INSULT","CONFESSION","THREAT","DEATH","SECRET")){
            ObjectNode plan=scene("NORMAL","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);
            beat.withArray("activeCharacters").add("listener");beat.put("eventType",eventType).put("reactionPriority","LISTENER").put("reactionSubjectId","listener").put("reactionReason","事件的意义先落在听者身上");
            shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");
            assertThat(validator.validate(plan)).as(eventType).extracting(ProductionModels.Risk::code).contains("REACTION_COVERAGE_MISSING");
        }
    }
    @Test void listenerPriorityPassesOnlyWhenTheDedicatedShotFocusesThatListener(){
        ObjectNode plan=scene("NORMAL","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);
        beat.withArray("activeCharacters").add("listener");beat.put("eventType","THREAT").put("reactionPriority","LISTENER").put("reactionSubjectId","listener").put("reactionReason","威胁是否奏效由听者的迟疑呈现");
        shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");shot(plan,"CLOSE_UP","STATIC",2,"SHOW_REACTION","beat-1");
        ObjectNode reaction=(ObjectNode)plan.path("shots").path(1);reaction.put("subject","listener").put("dialogueOwner","speaker");
        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).doesNotContain("REACTION_COVERAGE_MISSING","REACTION_SUBJECT_INVALID");
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
    @Test void rejectsPurposeLessInvisibleAndSemanticallyDuplicateShots(){
        ObjectNode plan=scene("NORMAL","LOW");shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");
        ObjectNode first=(ObjectNode)plan.path("shots").path(0),second=(ObjectNode)plan.path("shots").path(1);
        first.put("purpose","").put("shotPurpose","").put("action","").put("visualFocus","").put("subject","");
        second.put("purpose","重复看同一个动作").put("shotPurpose","重复看同一个动作").put("action","人物原地等待").put("visualFocus","人物").put("subject","speaker");
        ObjectNode duplicate=second.deepCopy();plan.withArray("shots").add(duplicate);

        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code)
            .contains("SHOT_NO_PURPOSE","NO_VISUAL_INFORMATION","DUPLICATE_INFORMATION","SHOT_CAN_BE_REMOVED");
    }
    @Test void actionShotWithoutStateInformationOrEmotionChangeIsRejected(){
        ObjectNode plan=scene("NORMAL","LOW");ObjectNode beat=(ObjectNode)plan.path("dramaticBeats").path(0);beat.put("emotionAfter","平静").put("informationReveal","");
        shot(plan,"MEDIUM","STATIC",3,"SHOW_ACTION","beat-1");ObjectNode shot=(ObjectNode)plan.path("shots").path(0);
        shot.put("purpose","人物原地等待").put("shotPurpose","人物原地等待").put("action","人物原地等待").put("visualFocus","人物").put("subject","speaker");
        ObjectNode state=mapper.createObjectNode().put("marker","unchanged");shot.set("startState",state);shot.set("endState",state.deepCopy());

        assertThat(validator.validate(plan)).extracting(ProductionModels.Risk::code).contains("NO_STATE_CHANGE","SHOT_CAN_BE_REMOVED");
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
