package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.provider.StructuredJson;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DirectorContractTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ValidatorFactory validatorFactory;private DirectorContract contract;private ObjectNode input,output;
    @BeforeEach void setup() throws Exception {
        validatorFactory=Validation.buildDefaultValidatorFactory();contract=new DirectorContract(mapper,validatorFactory.getValidator());
        input=(ObjectNode)mapper.readTree("""
          {"scene":{"duration":6,"state":{}},"assets":{
            "characters":[{"id":"actor","baseLookId":"look"}],
            "looks":[{"id":"look","characterId":"actor"}],
            "locations":[{"id":"yard","locationBible":{"fixedFeatures":[{"featureId":"NORTH_DOOR_RING","name":"北侧门环"},{"featureId":"WELL","name":"水井"}]}}],"props":[{"id":"bell"}]},"dialogues":[]}
          """);
        ObjectNode shot=(ObjectNode)mapper.readTree("""
          {"purpose":"老人听见门铃","feltIntent":"让观众意识到门外的声音不属于熟人","duration":3,"action":"老人凝视铁铃","visualFocus":"老人的眼睛","emotion":"警觉",
          "shotSize":"MEDIUM","cameraAngle":"EYE_LEVEL","cameraMovement":"STATIC","relationToPrevious":"ESTABLISHING","difficulty":"B",
          "cameraPlan":{"position":"院门南侧2米","height":"1.5米","distance":"2米","lensMm":50,"horizontalAngle":"朝北0度","verticalAngle":"水平0度","subjectPlacement":"左侧三分之一","focusPoint":"老人眼睛","depthOfField":"中等景深，门环可辨","lightingDirection":"西侧月光，从左后入射","movementPath":"固定","movementSpeed":"0米每秒"},
          "characterIds":["actor"],"propIds":["bell"],"dialogues":[],"authorizedChanges":[],
          "referenceViews":{"look":"FRONT","yard":"FRONT","bell":"SIDE"},
          "startState":{
          "characters":{"actor":{"identityId":"actor","lookId":"look","position":"院门南侧一米","lookDirection":"北侧门环","holding":"bell","pose":"站立","actionState":{"action":"OBSERVE_BELL","progress":1,"hand":"RIGHT","object":"bell"}}},
          "props":{"bell":{"holder":"actor","position":"老人右手","state":"完整"}}}}
          """);
        shot.put("beatId","beat-1").put("directorIntent","BUILD_TENSION").put("shotPurpose","让观众注意到门外异常")
            .put("subject","actor").putArray("secondarySubjects").add("bell");
        shot.set("blocking",mapper.createObjectNode().put("cameraWorldPosition","院门南侧2米").put("axis","院门—老人轴线")
            .put("keyObjectPositions","铁铃在老人右手，院门在北侧").put("doorWindowState","院门关闭，仅门缝透光").put("axisSide","A_SIDE").put("axisChangeReason","")
            .set("characters",mapper.createArrayNode().add(mapper.createObjectNode().put("characterId","actor").put("worldPosition","院门南侧一米")
                .put("facing","朝北").put("movementVector","STATIC").put("framePosition","画面左侧").put("screenDirection","FRAME_RIGHT").put("eyeLineTarget","北侧门环").put("eyeLineTargetType","LOCATION_FEATURE").put("eyeLineTargetId","NORTH_DOOR_RING")
                .put("visibleBodyPart","RIGHT_HAND").put("bodyFrameSide","IN_FRAME_LEFT").put("limbEntrySide","FRAME_LEFT").put("contactPoint","右手握住铃柄"))));
        ((ObjectNode)shot.path("blocking")).putArray("props").add(mapper.createObjectNode().put("propId","bell").put("worldPosition","老人右手"));
        ((ObjectNode)shot.path("blocking")).putArray("spatialAnchors").add(mapper.createObjectNode().put("subject","actor").put("anchorObject","院门").put("relation","SOUTH_OF").put("facing","NORTH").put("distance","1米").put("side","SOUTH"));
        shot.set("performancePlan",mapper.createObjectNode().put("primaryAction","凝视铁铃").put("microExpression","眉间收紧")
            .put("bodyLanguage","肩背微僵").put("gaze","视线锁定门环").put("gesture","右手稳握铃柄").put("actionUnits",1)
            .put("detailLevel","BASIC").put("microExpressionStage","NONE")
            .set("performanceCues",mapper.createObjectNode().put("eyes","").put("jaw","").put("breath","").put("hands","").put("shoulders","").put("pause","")));
        ((ObjectNode)shot.path("performancePlan")).set("propOperations",mapper.createArrayNode());
        ((ObjectNode)shot.path("performancePlan")).putArray("facsUnits");
        shot.set("visibilityPlan",mapper.createObjectNode().put("occlusion","NONE").put("requiredDetail","IDENTITY").set("visibleFeatures",mapper.createArrayNode().add("眼睛")));
        shot.put("expression","警觉压住恐惧").put("eyeLine","朝画面右侧门环").put("focus","老人眼睛")
            .put("dialogueOwner","").put("transition","CUT");
        shot.set("endState",shot.path("startState").deepCopy());output=mapper.createObjectNode();
        output.set("directorPlan",mapper.createObjectNode().put("planPurpose","从平静观察推进到异常警觉").put("scenePacing","TENSION_BUILD").put("shotRepetitionReason","").put("cameraStrategy","稳定机位建立空间后切反应")
            .set("styleProfile",mapper.createObjectNode().put("visualRhythm","稳定建立后切入反应").put("averageShotLength",3)
                .put("cameraActivity","LOW").put("closeUpPreference",0.6).put("reactionShotPreference",0.7)
                .put("compositionStyle","利用门框形成遮挡").put("tensionStyle","逐步缩小信息范围")));
        ObjectNode beat=mapper.createObjectNode().put("beatId","beat-1").put("beatPurpose","察觉异常")
            .put("action","老人观察铁铃").put("conflict","想确认声音又不敢开门").put("emotionBefore","平静")
            .put("emotionAfter","警觉").put("informationReveal","门外存在异常动静").put("setup","铁铃此前只会被熟人触碰").put("payoff","").put("eventType","REVEAL").put("reactionPriority","NONE").put("reactionSubjectId","").put("reactionReason","本节拍只有一名在场人物").put("importance","HIGH").put("suggestedDuration",6);
        ObjectNode knowledge=beat.putObject("knowledgeChange");knowledge.putArray("audienceLearns").add("门外动静异常");knowledge.putArray("characterChanges");
        beat.putArray("activeCharacters").add("actor");beat.putArray("storyFactChanges");beat.putArray("relationshipChanges");output.putArray("dramaticBeats").add(beat);
        output.putObject("sceneState").put("locationId","yard").put("time","午夜").put("lighting","西侧月光").put("spatialRelations","院门在北侧，井在东侧");
        output.putArray("presenceLedger").add(mapper.createObjectNode().put("characterId","actor").put("presence","PRESENT").put("visibility","FOREGROUND").put("anchor","院门南侧一米"));
        shot.putArray("offscreenCharacterIds");shot.putArray("exitedCharacterIds");
        ObjectNode reaction=shot.deepCopy().put("relationToPrevious","REACTION").put("directorIntent","SHOW_REACTION")
            .put("purpose","老人压住恐惧确认门外异常").put("shotPurpose","让观众看到老人意识到门外并非熟人")
            .put("action","老人停止摇铃并抬眼看向门缝").put("visualFocus","老人骤停的右手和抬起的视线");
        output.putArray("shots").add(shot).add(reaction);
    }
    @AfterEach void close(){validatorFactory.close();}
    @Test void requestSchemaAndValidationUseTheSameRelationEnum() {
        JsonNode schema=contract.schema(input),relation=schema.path("properties").path("shots").path("items").path("properties").path("relationToPrevious").path("enum");
        assertThat(mapper.convertValue(relation,String[].class)).containsExactly(Arrays.stream(ShotRelation.values()).filter(v->v!=ShotRelation.TIME_JUMP&&v!=ShotRelation.LOCATION_CHANGE).map(Enum::name).toArray(String[]::new));
        StructuredJson json=new StructuredJson(mapper,validatorFactory.getValidator());
        assertThatCode(()->json.schema(mapper.convertValue(schema,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}))).doesNotThrowAnyException();
        assertThatCode(()->contract.validate(output,input,"provider-123")).doesNotThrowAnyException();
    }
    @Test void directorOutputSeparatesDramaticBeatsIntentAndStructuredShotPlan() {
        JsonNode schema=contract.schema(input),root=schema.path("properties"),shot=root.path("shots").path("items").path("properties");
        assertThat(root.has("directorPlan")).isTrue();
        assertThat(root.has("dramaticBeats")).isTrue();
        assertThat(shot.fieldNames()).toIterable().contains("beatId","directorIntent","subject","secondarySubjects","blocking","performancePlan","dialogueOwner");
        assertThat(root.path("dramaticBeats").path("items").path("properties").fieldNames()).toIterable()
            .contains("eventType","reactionPriority","reactionSubjectId","reactionReason");
    }
    @Test void characterActionStateIsAProviderNeutralStructuredContract(){
        JsonNode actionState=contract.schema(input).path("properties").path("shots").path("items").path("properties")
            .path("startState").path("properties").path("characters").path("properties").path("actor").path("properties").path("actionState");
        assertThat(actionState.path("type").asText()).isEqualTo("object");
        assertThat(actionState.path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("action","progress","hand","object");
        assertThat(actionState.path("properties").path("progress").path("minimum").asDouble()).isZero();
        assertThat(actionState.path("properties").path("progress").path("maximum").asDouble()).isEqualTo(1);
    }
    @Test void formalContractCarriesPresencePerformanceAndTimingGuards(){
        JsonNode root=contract.schema(input).path("properties"),shotSchema=root.path("shots").path("items").path("properties");
        assertThat(root.has("presenceLedger")).isTrue();
        assertThat(shotSchema.fieldNames()).toIterable().contains("offscreenCharacterIds","exitedCharacterIds");
        assertThat(shotSchema.path("performancePlan").path("properties").fieldNames()).toIterable()
            .contains("detailLevel","microExpressionStage","performanceCues","facsUnits");
        input.putObject("episodeScript").put("script","周伯：别开门，他没有影子。");
        ObjectNode line=mapper.createObjectNode().put("characterId","actor").put("semanticText","别开门，他没有影子。").put("subtitleText","别开门，他没有影子。")
            .put("sourceScript","周伯：别开门，他没有影子。").put("emotion","恐惧中克制").put("startMs",0).put("endMs",900);
        shot(0).withArray("dialogues").add(line);shot(0).put("dialogueOwner","actor");
        assertThatThrownBy(()->contract.validate(output,input,"timing-request")).hasMessageContaining("DIALOGUE_ESTIMATED_OVERRUN");
    }
    @Test void formalContractExecutesFirstShotAndFramingValidators(){
        shot(0).put("subject","他和她").put("action","有人伸出一只手");
        assertThatThrownBy(()->contract.validate(output,input,"anchor-request")).hasMessageContaining("MULTI_SUBJECT_AMBIGUITY");
        shot(0).put("subject","actor").put("action","周伯伸出右手").put("shotSize","CLOSE_UP");
        ((ObjectNode)shot(0).path("blocking").path("characters").path(0)).put("worldPosition","ROOM_FAR_NORTH");
        ((ObjectNode)shot(0).path("visibilityPlan")).put("requiredDetail","PROP_DETAIL");
        assertThatThrownBy(()->contract.validate(output,input,"framing-request")).hasMessageContaining("SHOT_SCALE_CONFLICT");
    }
    @Test void blockingPlansExposeStructuredWorldSpaceAnchors() {
        JsonNode full=contract.schema(input).path("properties").path("shots").path("items").path("properties").path("blocking").path("properties").path("spatialAnchors");
        JsonNode staged=contract.planSchema(input).path("properties").path("shotSkeletons").path("items").path("properties").path("basicBlocking").path("properties").path("spatialAnchors");
        assertThat(full.path("type").asText()).isEqualTo("array");
        assertThat(staged.path("type").asText()).isEqualTo("array");
        assertThat(full.path("items").path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("subject","anchorObject","relation","facing","distance","side");
    }
    @Test void blockingIrCarriesStructuredPropPositionsAndMovementVectors(){
        JsonNode blocking=contract.planSchema(input).path("properties").path("shotSkeletons").path("items").path("properties").path("basicBlocking").path("properties");
        assertThat(blocking.path("characters").path("items").path("properties").has("movementVector")).isTrue();
        assertThat(blocking.path("props").path("type").asText()).isEqualTo("array");
        assertThat(blocking.path("props").path("items").path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("propId","worldPosition");
    }
    @Test void directorContractRejectsWorldAnchorFlipAcrossAdjacentShots() {
        ((ObjectNode)shot(1).path("blocking").path("spatialAnchors").path(0)).put("relation","NORTH_OF").put("side","NORTH");
        assertThatThrownBy(()->contract.validate(output,input,"provider-spatial"))
                .hasMessageContaining("SPATIAL_ANCHOR_WORLD_RELATION_CHANGED");
    }
    @Test void finalShotSchemaRejectsLegacyScreenDirectionAliases(){
        JsonNode direction=contract.schema(input).path("properties").path("shots").path("items").path("properties")
            .path("blocking").path("properties").path("characters").path("items").path("properties").path("screenDirection");
        assertThat(mapper.convertValue(direction.path("enum"),String[].class))
            .containsExactly("FRAME_LEFT","FRAME_RIGHT","INTO_DEPTH","OUT_OF_DEPTH","STATIC");
        ((ObjectNode)shot(0).path("blocking").path("characters").path(0)).put("screenDirection","RIGHT");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("screenDirection");
    }
    @Test void twentyFourSecondSchemaHasDynamicShotBeatAndTextBounds(){
        input.with("scene").put("duration",24);input.putObject("directorStyleProfile").put("averageShotLength",2.6);
        JsonNode schema=contract.schema(input),root=schema.path("properties");
        assertThat(root.path("shots").path("minItems").asInt()).isEqualTo(8);
        assertThat(root.path("shots").path("maxItems").asInt()).isEqualTo(13);
        assertThat(root.path("dramaticBeats").path("maxItems").asInt()).isEqualTo(13);
        assertThat(root.path("shots").path("items").path("properties").path("shotPurpose").path("maxLength").asInt()).isBetween(40,300);
        assertThat(root.path("shots").path("items").path("properties").path("cameraPlan").path("properties").path("position").path("maxLength").asInt()).isBetween(40,300);
    }
    @Test void twentyFourSecondSceneCanUseThreeEightSecondNarrativeShots(){
        input.with("scene").put("duration",24);
        input.putObject("directorStyleProfile").put("averageShotLength",8);
        JsonNode planShots=contract.planSchema(input).path("properties").path("shotSkeletons");
        JsonNode finalShots=contract.schema(input).path("properties").path("shots");
        assertThat(planShots.path("minItems").asInt()).isLessThanOrEqualTo(3);
        assertThat(planShots.path("maxItems").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(finalShots.path("minItems").asInt()).isLessThanOrEqualTo(3);
        assertThat(finalShots.path("maxItems").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(planShots.path("items").path("properties").path("duration").path("maximum").asDouble()).isGreaterThanOrEqualTo(8);
        assertThat(finalShots.path("items").path("properties").path("duration").path("maximum").asDouble()).isGreaterThanOrEqualTo(8);
    }
    @Test void editDurationKeepsTheAuthoredShotDurationBeyondProviderClipLimits(){
        shot(0).put("duration",8);
        ArrayNode materialized=contract.materialize(output,input);
        assertThat(materialized.path(0).path("editDuration").asDouble()).isEqualTo(8);
    }
    @Test void planningNeverOffersMoreShotsThanTheEditorCanAssemble(){
        input.putObject("directorStyleProfile").put("averageShotLength",1);
        JsonNode shots=contract.planSchema(input).path("properties").path("shotSkeletons");
        assertThat(shots.path("maxItems").asInt()).isLessThanOrEqualTo(4);
        assertThat(shots.path("minItems").asInt()).isLessThanOrEqualTo(shots.path("maxItems").asInt());
        assertThat(shots.path("items").path("properties").path("duration").path("minimum").asDouble()).isEqualTo(1.25);
    }
    @Test void stagedSchemasKeepRepeatedWorldStateOutOfEveryShot(){
        input.put("sceneTargetDurationSeconds",24);input.putObject("directorStyleProfile").put("averageShotLength",2.6);
        JsonNode plan=contract.planSchema(input),root=plan.path("properties"),skeleton=root.path("shotSkeletons").path("items").path("properties");
        assertThat(root.fieldNames()).toIterable().contains("directorPlan","dramaticBeats","sceneState","sceneInitialState","shotSkeletons");
        assertThat(root.path("shotSkeletons").path("minItems").asInt()).isEqualTo(8);
        assertThat(root.path("shotSkeletons").path("maxItems").asInt()).isEqualTo(13);
        assertThat(skeleton.fieldNames()).toIterable().doesNotContain("cameraPlan","performancePlan","startState","endState","referenceViews");
        Set<String> screenDirections=new LinkedHashSet<>();skeleton.path("basicBlocking").path("properties").path("characters").path("items").path("properties").path("screenDirection").path("enum").forEach(value->screenDirections.add(value.asText()));
        assertThat(screenDirections).containsExactlyInAnyOrder("FRAME_LEFT","FRAME_RIGHT","INTO_DEPTH","OUT_OF_DEPTH","STATIC");
        assertThat(contract.schema(input).path("properties").path("shots").path("items").path("properties").path("authorizedChanges").path("items").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("path","from","to","reason","atSeconds");

        ObjectNode detailInput=input.deepCopy();detailInput.set("shotSkeletons",mapper.createArrayNode().add(mapper.createObjectNode()));
        JsonNode detail=contract.detailSchema(detailInput),detailRoot=detail.path("properties"),detailShot=detailRoot.path("shots").path("items").path("properties");
        assertThat(detailRoot.path("shots").path("minItems").asInt()).isEqualTo(1);
        assertThat(detailRoot.path("shots").path("maxItems").asInt()).isEqualTo(1);
        assertThat(detailShot.fieldNames()).toIterable().contains("cameraPlan","performancePlan","stateChanges","referenceViews");
        assertThat(detailShot.fieldNames()).toIterable().doesNotContain("startState","endState","directorPlan","dramaticBeats");
        assertThat(detailShot.path("stateChanges").path("items").path("properties").fieldNames()).toIterable().doesNotContain("from");
        assertThat(detailShot.path("blocking").path("properties").fieldNames()).toIterable().containsExactly("characters");
        assertThat(detailShot.path("referenceViews").path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("characterViews","locationView","propViews");
        assertThat(detailShot.path("referenceViews").path("properties").path("locationView").path("enum"))
            .extracting(JsonNode::asText).containsExactlyInAnyOrder("FRONT","REVERSE","SIDE");
        assertThat(detailShot.path("dialogues").path("items").path("properties").fieldNames()).toIterable()
            .doesNotContain("sourceScript");
        assertThat(detailShot.path("cameraPlan").path("properties").fieldNames()).toIterable().contains("lensPreset").doesNotContain("lensMm");
    }
    @Test void denseTwentyFourSecondScenesCanUseTheFullPhysicallyPossibleShotRange(){
        input.put("sceneTargetDurationSeconds",24);input.putObject("directorStyleProfile").put("averageShotLength",3);
        JsonNode shots=contract.planSchema(input).path("properties").path("shotSkeletons");
        assertThat(shots.path("minItems").asInt()).isEqualTo(7);
        assertThat(shots.path("maxItems").asInt()).isEqualTo(12);
    }
    @Test void shotDetailRequiresExplicitBodySourceAndLimbEntrySides(){
        ObjectNode detailInput=input.deepCopy();detailInput.set("shotSkeletons",mapper.createArrayNode().add(mapper.createObjectNode()));
        JsonNode actor=contract.detailSchema(detailInput).path("properties").path("shots").path("items").path("properties")
            .path("blocking").path("properties").path("characters").path("items");
        assertThat(actor.path("properties").fieldNames()).toIterable()
            .contains("eyeLineTargetType","eyeLineTargetId","visibleBodyPart","bodyFrameSide","limbEntrySide","contactPoint");
        assertThat(actor.path("required")).extracting(JsonNode::asText)
            .contains("eyeLineTargetType","eyeLineTargetId","visibleBodyPart","bodyFrameSide","limbEntrySide","contactPoint");
    }
    @Test void stateDeltasAndBlockingStartValuesComeFromTheServerContinuityLedger(){
        ObjectNode skeleton=mapper.createObjectNode().put("shotIndex",1).put("purpose","老人走向水井").put("feltIntent","让观众注意老人正在接近危险来源").put("action","老人从门边走到水井旁")
            .put("beatId","beat-1").put("directorIntent","SHOW_ACTION").put("subject","actor").put("dialogueOwner","actor").put("transition","CUT")
            .put("duration",3).put("shotSize","MEDIUM").put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("relationToPrevious","ESTABLISHING");
        skeleton.putArray("secondarySubjects");skeleton.set("characterIds",shot(0).path("characterIds").deepCopy());skeleton.set("propIds",shot(0).path("propIds").deepCopy());
        skeleton.set("basicBlocking",shot(0).path("blocking").deepCopy());
        ObjectNode batch=input.deepCopy();batch.set("sceneState",output.path("sceneState").deepCopy());batch.set("currentState",shot(0).path("startState").deepCopy());batch.putArray("shotSkeletons").add(skeleton);batch.set("previousShotContinuity",mapper.createObjectNode());
        batch.putObject("episodeScript").put("script","周伯（字幕：门外有人。）\n动作继续。 ");
        ObjectNode detail=mapper.createObjectNode().put("shotIndex",1).put("visualFocus","老人移动的脚步").put("emotion","警觉").put("expression","眉间收紧").put("eyeLine","看向水井").put("focus","老人脚步").put("difficulty","B");
        for(String field:List.of("performancePlan","visibilityPlan"))detail.set(field,shot(0).path(field).deepCopy());
        ObjectNode selectedCamera=((ObjectNode)shot(0).path("cameraPlan")).deepCopy();selectedCamera.remove("lensMm");selectedCamera.put("lensPreset","LENS_50MM");detail.set("cameraPlan",selectedCamera);
        detail.putArray("dialogues").add(mapper.createObjectNode().put("characterId","actor").put("semanticText","门外有人。").put("subtitleText","门外有人。").put("emotion","压低声音警告").put("startMs",100).put("endMs",1800));
        ObjectNode selectedViews=detail.putObject("referenceViews");selectedViews.putArray("characterViews").add("FRONT");selectedViews.put("locationView","FRONT");selectedViews.putArray("propViews").add("SIDE");
        ObjectNode framing=detail.putObject("blocking");framing.putArray("characters").addObject().put("characterId","actor").put("framePosition","画面左三分之一").put("eyeLineTarget","水井").put("eyeLineTargetType","LOCATION_FEATURE").put("eyeLineTargetId","WELL")
            .put("visibleBodyPart","WHOLE_BODY").put("bodyFrameSide","IN_FRAME_LEFT").put("limbEntrySide","NONE").put("contactPoint","");
        detail.putArray("stateChanges").add(mapper.createObjectNode().put("path","characters.actor.position").put("to","水井旁").put("reason","本镜明确拍摄老人走到水井旁").put("atSeconds",2.2));
        ObjectNode details=mapper.createObjectNode();details.putArray("shots").add(detail);
        ObjectNode result=contract.reconstructBatch(details,batch,"request-delta");
        assertThat(result.path("shots").path(0).path("startState").path("characters").path("actor").path("position").asText()).isEqualTo("院门南侧一米");
        assertThat(result.path("shots").path(0).path("endState").path("characters").path("actor").path("position").asText()).isEqualTo("水井旁");
        assertThat(result.path("shots").path(0).path("blocking").path("characters").path(0).path("worldPosition").asText()).isEqualTo("院门南侧一米");
        assertThat(result.path("shots").path(0).path("referenceViews")).isEqualTo(shot(0).path("referenceViews"));
        assertThat(result.path("shots").path(0).path("dialogues").path(0).path("sourceScript").asText()).isEqualTo("周伯（字幕：门外有人。）");
        assertThat(result.path("shots").path(0).path("cameraPlan").path("lensMm").asInt()).isEqualTo(50);
        assertThat(result.path("shots").path(0).path("authorizedChanges").path(0).path("path").asText()).isEqualTo("characters.actor.position");
        assertThat(result.path("shots").path(0).path("authorizedChanges").path(0).path("from").asText()).isEqualTo("院门南侧一米");
        ((ObjectNode)detail.path("stateChanges").path(0)).put("from","模型不应提供旧值");
        assertThatThrownBy(()->contract.reconstructBatch(details,batch,"request-delta")).hasMessageContaining("from");
    }
    @Test void complexMultiPersonMultiAssetSceneStillUsesOneStrictShotDetail(){
        for(int i=2;i<=6;i++){String actor="actor-"+i,look="look-"+i;input.withObject("assets").withArray("characters").add(mapper.createObjectNode().put("id",actor).put("baseLookId",look));input.withObject("assets").withArray("looks").add(mapper.createObjectNode().put("id",look).put("characterId",actor));}
        for(int i=2;i<=6;i++)input.withObject("assets").withArray("props").add(mapper.createObjectNode().put("id","prop-"+i));
        input.put("sceneTargetDurationSeconds",60);input.putObject("directorStyleProfile").put("averageShotLength",3);
        JsonNode plan=contract.planSchema(input);assertThat(plan.path("properties").path("shotSkeletons").path("minItems").asInt()).isBetween(16,25);assertThat(plan.path("properties").path("shotSkeletons").path("maxItems").asInt()).isBetween(16,25);
        ObjectNode batch=input.deepCopy();ArrayNode skeletons=batch.putArray("shotSkeletons");skeletons.add(mapper.createObjectNode());
        JsonNode detail=contract.detailSchema(batch),shots=detail.path("properties").path("shots");
        assertThat(shots.path("minItems").asInt()).isEqualTo(1);assertThat(shots.path("maxItems").asInt()).isEqualTo(1);
        assertThat(shots.path("items").path("properties").path("blocking").path("properties").path("characters").path("maxItems").asInt()).isEqualTo(6);
        assertThat(detail.toString().length()).isLessThan(25_000);
    }
    @Test void resolvedDirectorStyleIsServerOwnedAndCannotBeChangedByTheModel() {
        input.set("directorStyleProfile",output.path("directorPlan").path("styleProfile").deepCopy());
        ((ObjectNode)output.path("directorPlan").path("styleProfile")).put("averageShotLength",4.5);
        ObjectNode canonical=contract.canonicalizeTrustedFields(output,input);
        assertThat(canonical.path("directorPlan").path("styleProfile")).isEqualTo(input.path("directorStyleProfile"));
        assertThat(output.path("directorPlan").path("styleProfile").path("averageShotLength").asDouble()).isEqualTo(4.5);
        assertThatCode(()->contract.validate(canonical,input,"provider-123")).doesNotThrowAnyException();
    }
    @Test void canonicalPlanBridgesEmotionWordingForCharactersSharedByAdjacentBeats(){
        ObjectNode second=((ObjectNode)output.path("dramaticBeats").path(0)).deepCopy().put("beatId","beat-2")
            .put("emotionBefore","警觉紧绷").put("emotionAfter","恐慌");
        output.withArray("dramaticBeats").add(second);
        ObjectNode canonical=contract.canonicalizePlan(output,input);
        assertThat(canonical.path("dramaticBeats").path(1).path("emotionBefore").asText()).isEqualTo("警觉");
        assertThat(output.path("dramaticBeats").path(1).path("emotionBefore").asText()).isEqualTo("警觉紧绷");
    }
    @Test void canonicalPlanMakesDirectHoldingBidirectionalWithoutTreatingContainedPropsAsHandheld(){
        ObjectNode plan=output.deepCopy();ObjectNode initial=plan.putObject("sceneInitialState");
        initial.putObject("characters").putObject("actor").put("holding","bag");
        ObjectNode props=initial.putObject("props");props.putObject("bag").put("holder","actor").put("position","人物右手");
        props.putObject("photo").put("holder","actor").put("position","位于种子袋内部");
        ObjectNode canonical=contract.canonicalizePlan(plan,input);
        assertThat(canonical.path("sceneInitialState").path("props").path("bag").path("holder").asText()).isEqualTo("actor");
        assertThat(canonical.path("sceneInitialState").path("props").path("photo").path("holder").asText()).isEmpty();
        assertThat(plan.path("sceneInitialState").path("props").path("photo").path("holder").asText()).isEqualTo("actor");
    }
    @Test void planDerivesActiveCharactersFromItsShotSkeletonsWhenTheModelOmitsThem(){
        ObjectNode plan=output.deepCopy();plan.set("shotSkeletons",plan.remove("shots"));((ObjectNode)plan.path("dramaticBeats").path(0)).remove("activeCharacters");
        JsonNode required=contract.planSchema(input).path("properties").path("dramaticBeats").path("items").path("required");
        assertThat(mapper.convertValue(required,String[].class)).doesNotContain("activeCharacters");
        ObjectNode canonical=contract.canonicalizePlan(plan,input);
        assertThat(canonical.path("dramaticBeats").path(0).path("activeCharacters")).containsExactly(mapper.getNodeFactory().textNode("actor"));
    }
    @Test void canonicalPlanKeepsThePreviousAxisSideWhenNoVisibleCrossingWasPlanned(){
        ObjectNode plan=output.deepCopy();ArrayNode skeletons=plan.putArray("shotSkeletons");
        for(JsonNode source:output.path("shots")){
            ObjectNode skeleton=((ObjectNode)source).deepCopy();skeleton.set("basicBlocking",skeleton.remove("blocking"));skeletons.add(skeleton);
        }
        ((ObjectNode)skeletons.path(0).path("basicBlocking")).put("axisSide","A_SIDE").put("axisChangeReason","");
        ((ObjectNode)skeletons.path(1).path("basicBlocking")).put("axisSide","B_SIDE").put("axisChangeReason","");
        ObjectNode canonical=contract.canonicalizePlan(plan,input);
        assertThat(canonical.path("shotSkeletons").path(1).path("basicBlocking").path("axisSide").asText()).isEqualTo("A_SIDE");
        assertThat(plan.path("shotSkeletons").path(1).path("basicBlocking").path("axisSide").asText()).isEqualTo("B_SIDE");
    }
    @Test void canonicalPlanAllocatesModelDurationsToTheExactServerOwnedSceneBudget(){
        input.put("sceneTargetDurationSeconds",5);ObjectNode plan=output.deepCopy();ArrayNode skeletons=plan.putArray("shotSkeletons");
        for(JsonNode source:output.path("shots")){ObjectNode skeleton=((ObjectNode)source).deepCopy();skeleton.set("basicBlocking",skeleton.remove("blocking"));skeletons.add(skeleton);}
        assertThat(skeletons).extracting(value->value.path("duration").asDouble()).containsExactly(3d,3d);
        ObjectNode canonical=contract.canonicalizePlan(plan,input);
        assertThat(canonical.path("shotSkeletons")).extracting(value->value.path("duration").asDouble()).containsExactly(2.5d,2.5d);
        assertThat(plan.path("shotSkeletons")).extracting(value->value.path("duration").asDouble()).containsExactly(3d,3d);
    }
    @Test void unapprovedStoryTruthMutationsAreRemovedBeforeProductionValidation() {
        ObjectNode beat=(ObjectNode)output.path("dramaticBeats").path(0);
        beat.withArray("storyFactChanges").add("模型擅自声明的新事实");
        beat.withArray("relationshipChanges").add("模型擅自改变的人物关系");
        ObjectNode canonical=contract.canonicalizeTrustedFields(output,input);
        assertThat(canonical.path("dramaticBeats").path(0).path("storyFactChanges")).isEmpty();
        assertThat(canonical.path("dramaticBeats").path(0).path("relationshipChanges")).isEmpty();
        assertThat(output.path("dramaticBeats").path(0).path("storyFactChanges")).hasSize(1);
        assertThatCode(()->contract.validate(canonical,input,"provider-123")).doesNotThrowAnyException();
    }
    @Test void offscreenStateReturnedByTheModelCannotOverwriteTheServerLedger() {
        ObjectNode second=shot(1);second.putArray("characterIds");second.putArray("offscreenCharacterIds").add("actor");second.putArray("propIds");
        second.putObject("referenceViews").put("yard","FRONT");
        ObjectNode canonical=contract.canonicalizeTrustedFields(output,input);
        assertThat(canonical.path("shots").path(1).path("startState").path("characters")).isEmpty();
        assertThat(canonical.path("shots").path(1).path("startState").path("props")).isEmpty();
        assertThat(output.path("shots").path(1).path("startState").path("characters")).isNotEmpty();
        assertThatCode(()->contract.validate(canonical,input,"provider-123")).doesNotThrowAnyException();
    }
    @Test void everyBeatNeedsShotCoverageAndShotsCannotReferenceUnknownBeats() {
        ObjectNode second=((ObjectNode)output.path("dramaticBeats").path(0)).deepCopy().put("beatId","beat-2").put("beatPurpose","确认危险").put("emotionBefore","警觉");
        output.withArray("dramaticBeats").add(second);
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.dramaticBeats[1].beatId");
        shot(1).put("beatId","beat-2");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        shot(1).put("beatId","missing");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].beatId");
    }
    @Test void blockingKeepsTheSceneAxisAndScreenDirectionUnlessChangeIsExplained(){
        ((ObjectNode)shot(0).path("blocking")).put("axisSide","A_SIDE").put("axisChangeReason","");
        ((ObjectNode)shot(1).path("blocking")).put("axisSide","B_SIDE").put("axisChangeReason","");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].blocking.axisSide");
        ((ObjectNode)shot(1).path("blocking")).put("axisSide","A_SIDE");shot(1).put("relationToPrevious","CONTINUOUS");
        ((ObjectNode)shot(1).path("blocking").path("characters").path(0)).put("screenDirection","FRAME_LEFT");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("screenDirection");
        ((ObjectNode)shot(1).path("blocking").path("characters").path(0)).put("screenDirection","FRAME_RIGHT");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        ((ObjectNode)shot(1).path("blocking").path("characters").path(0)).put("screenDirection","INTO_DEPTH");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
    }
    @Test void eyelineTargetMustResolveToADeclaredCharacterPropOrLocationFeature(){
        ((ObjectNode)shot(0).path("blocking").path("characters").path(0)).put("eyeLineTarget","不存在的影子").put("eyeLineTargetType","CHARACTER").put("eyeLineTargetId","missing-character");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("eyeLineTarget");
    }
    @Test void directorCannotInventStoryFactsOrRelationshipChanges(){
        ((ObjectNode)output.path("dramaticBeats").path(0)).withArray("storyFactChanges").add("fact-not-in-script");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("storyFactChanges");
        input.withObject("scene").putArray("storyFactChanges").add("fact-not-in-script");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        ((ObjectNode)output.path("dramaticBeats").path(0)).withArray("relationshipChanges").add("relation-not-in-script");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("relationshipChanges");
    }
    @Test void dialogueOwnerMustMatchSpeechWhileTheShotCanFocusOnAListenersReaction(){
        input.putObject("episodeScript").put("script","周伯：门外的人不是老李。");
        ObjectNode listener=mapper.createObjectNode().put("id","listener").put("name","陈木根").put("baseLookId","listener-look");input.withObject("assets").withArray("characters").add(listener);
        input.withObject("assets").withArray("looks").add(mapper.createObjectNode().put("id","listener-look").put("characterId","listener"));
        output.withArray("presenceLedger").add(mapper.createObjectNode().put("characterId","listener").put("presence","PRESENT").put("visibility","BACKGROUND").put("anchor","供桌右侧"));
        for(int i=0;i<2;i++){
            ObjectNode s=shot(i);s.withArray("characterIds").add("listener");s.withObject("referenceViews").put("listener-look","FRONT");
            ObjectNode listenerState=mapper.createObjectNode().put("identityId","listener").put("lookId","listener-look").put("position","供桌右侧")
                .put("lookDirection","朝周伯").put("holding","").put("pose","站立");
            listenerState.set("actionState",mapper.createObjectNode().put("action","LISTEN").put("progress",1).put("hand","NONE").put("object",""));
            s.withObject("startState").withObject("characters").set("listener",listenerState);s.withObject("endState").withObject("characters").set("listener",listenerState.deepCopy());
            s.withObject("blocking").withArray("characters").add(mapper.createObjectNode().put("characterId","listener").put("worldPosition","供桌右侧")
                .put("facing","朝周伯").put("movementVector","STATIC").put("framePosition","画面右侧").put("screenDirection","FRAME_LEFT").put("eyeLineTarget","周伯").put("eyeLineTargetType","CHARACTER").put("eyeLineTargetId","actor")
                .put("visibleBodyPart","FACE").put("bodyFrameSide","IN_FRAME_RIGHT").put("limbEntrySide","NONE").put("contactPoint",""));
        }
        ObjectNode line=mapper.createObjectNode().put("characterId","actor").put("semanticText","门外的人不是老李。").put("subtitleText","门外的人不是老李。")
            .put("sourceScript","周伯：门外的人不是老李。").put("emotion","压低声音揭示线索").put("startMs",100).put("endMs",2800);
        shot(1).withArray("dialogues").add(line);shot(1).put("dialogueOwner","").put("subject","listener").put("directorIntent","SHOW_REACTION");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].dialogueOwner");
        shot(1).put("dialogueOwner","actor");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        shot(1).put("subject","陈木根");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
    }
    @Test void invalidRelationKeepsProviderIdAndPreciseShotPath() {
        shot(1).put("relationToPrevious","切换");
        assertThatThrownBy(()->contract.validate(output,input,"provider-123")).isInstanceOf(ProviderException.class).hasMessageContaining("$.shots[1].relationToPrevious");
        try {contract.validate(output,input,"provider-123");fail("must fail");}catch(ProviderException e){assertThat(e.requestId()).isEqualTo("provider-123");}
    }
    @Test void cameraPlanCannotBeMissingOrUseTextForFocalLength() {
        ((ObjectNode)shot(0).path("cameraPlan")).put("lensMm","50毫米");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].cameraPlan.lensMm");
    }
    @Test void visiblePersonNeedsCurrentLookAndCannotUseAnotherCharactersLook() {
        ((ObjectNode)shot(0).path("startState").path("characters").path("actor")).put("lookId","unknown-look");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].startState.characters.actor.lookId");
    }
    @Test void emptyStateIsRejectedBeforePersistence() {
        shot(0).set("startState",mapper.createObjectNode());
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].startState.characters");
    }
    @Test void personAndPropOwnershipMustAgree() {
        ((ObjectNode)shot(0).path("startState").path("props").path("bell")).put("holder","");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].startState.characters.actor.holding");
    }
    @Test void reactionShotCannotMoveActorWithoutAnExplainedChange() {
        ((ObjectNode)shot(1).path("startState").path("characters").path("actor")).put("position","水井旁");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].startState.characters.actor.position");
        shot(1).withArray("authorizedChanges").add(mapper.createObjectNode().put("path","characters.actor.position").put("from","院门南侧一米").put("to","水井旁").put("reason","剧本明确省略老人从门口走向水井的步行过程").put("atSeconds",1));
        ((ObjectNode)shot(1).path("blocking").path("characters").path(0)).put("worldPosition","水井旁");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
    }
    @Test void everyVisibleAssetNeedsOneRelevantViewAndNoUnrelatedView() {
        ((ObjectNode)shot(0).path("referenceViews")).remove("look");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].referenceViews");
    }
    @Test void onlyEstablishingCanStartTheSceneAndDurationMustMatch() {
        shot(0).put("relationToPrevious","CONTINUOUS");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].relationToPrevious");
        shot(0).put("relationToPrevious","ESTABLISHING");input.withObject("scene").put("duration",20);
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots.duration");
    }
    @Test void sceneDurationMustMatchExactlyWithinRoundingTolerance() {
        shot(0).put("duration",2.5);shot(1).put("duration",2.0);
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots.duration");
    }
    @Test void sceneInvariantsAreRequiredOnceAndCannotBeRepeatedOrChangedByAShot() {
        JsonNode global=output.remove("sceneState");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.sceneState");
        output.set("sceneState",global);
        for(String field:List.of("locationId","time","lighting","spatialRelations")) {
            ((ObjectNode)shot(1).path("startState")).put(field,"擅自改写");
            assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].startState."+field);
            ((ObjectNode)shot(1).path("startState")).remove(field);
        }
        shot(1).put("locationId","yard");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].locationId");
        shot(1).remove("locationId");
        for(String relation:List.of("TIME_JUMP","LOCATION_CHANGE")) {
            shot(1).put("relationToPrevious",relation);
            assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].relationToPrevious");
        }
        shot(1).put("relationToPrevious","REACTION");
        shot(1).withArray("authorizedChanges").add(mapper.createObjectNode().put("path","time").put("from","午夜").put("to","午夜稍后").put("reason","人物敲门结束").put("atSeconds",1));
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[1].authorizedChanges[0].path");
    }
    @Test void materializationAddsSharedSceneStateWithoutChangingProviderOutput() {
        ObjectNode raw=output.deepCopy();contract.validate(output,input,"p");
        ArrayNode saved=contract.materialize(output,input);
        for(JsonNode shot:saved) {
            assertThat(shot.path("locationId").asText()).isEqualTo("yard");
            for(String field:List.of("locationId","time","lighting","spatialRelations")) {
                assertThat(shot.path("startState").path(field)).isEqualTo(output.path("sceneState").path(field));
                assertThat(shot.path("endState").path(field)).isEqualTo(output.path("sceneState").path(field));
            }
        }
        assertThat(output).isEqualTo(raw);
    }
    @Test void materializationKeepsAuthoredEditDurationWithoutInventingProviderDuration(){
        contract.validate(output,input,"p");ArrayNode saved=contract.materialize(output,input);
        assertThat(saved).allSatisfy(shot->{
            assertThat(shot.path("editDuration").asDouble()).isEqualTo(shot.path("duration").asDouble());
            assertThat(shot.has("providerDuration")).isFalse();
            assertThat(shot.path("durationBasis").asText()).contains("intent=");
        });
    }
    @Test void dialogueMustComeFromApprovedScriptAndFitTheShot() {
        input.putObject("episodeScript").put("script","周伯压低声音：别开门，他没有影子。");
        ObjectNode line=mapper.createObjectNode().put("characterId","actor").put("semanticText","别开门，他没有影子。").put("subtitleText","别开门，他没有影子。")
            .put("sourceScript","周伯压低声音：别开门，他没有影子。").put("emotion","压住恐惧提醒同伴").put("startMs",200).put("endMs",2900);
        shot(0).withArray("dialogues").add(line);
        shot(0).put("dialogueOwner","actor");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        line.put("semanticText","大家跟我打僵尸！").put("subtitleText","大家跟我打僵尸！");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].dialogues[0].sourceScript");
        line.put("semanticText","别开门，他没有影子。").put("subtitleText","别开门，他没有影子。").put("endMs",5000);
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[0].dialogues[0].startMs");
    }
    @Test void offscreenCharacterStateIsRetainedAndCheckedOnReentry() {
        ((ObjectNode)input.path("scene")).put("duration",9);
        ObjectNode first=shot(0).deepCopy();
        ObjectNode hidden=first.deepCopy().put("relationToPrevious","REACTION");
        hidden.putArray("characterIds");hidden.putArray("offscreenCharacterIds").add("actor");hidden.putArray("propIds");hidden.putObject("referenceViews").put("yard","FRONT");
        ((ObjectNode)hidden.path("startState").path("characters")).removeAll();
        ((ObjectNode)hidden.path("startState").path("props")).removeAll();
        ((ObjectNode)hidden.path("endState").path("characters")).removeAll();
        ((ObjectNode)hidden.path("endState").path("props")).removeAll();
        ObjectNode reentry=first.deepCopy().put("relationToPrevious","REACTION");
        ((ObjectNode)reentry.path("startState").path("characters").path("actor")).put("position","水井旁");
        ArrayNode shots=output.putArray("shots");shots.add(first).add(hidden).add(reentry);
        assertThatThrownBy(()->contract.validate(output,input,"p"))
            .hasMessageContaining("$.shots[2].startState.characters.actor.position");
        ((ObjectNode)reentry.path("startState").path("characters").path("actor")).put("position","院门南侧一米");
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
        ArrayNode saved=contract.materialize(output,input);
        assertThat(saved.path(1).path("startState").path("characters").path("actor").path("holding").asText()).isEqualTo("bell");
        assertThat(saved.path(1).path("endState").path("props").path("bell").path("holder").asText()).isEqualTo("actor");
        ((ObjectNode)reentry.path("startState").path("characters").path("actor")).put("holding","");
        ((ObjectNode)reentry.path("startState").path("props").path("bell")).put("holder","");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[2].startState.characters.actor.holding");
        ((ObjectNode)reentry.path("startState").path("characters").path("actor")).put("holding","bell").put("lookId","changed-look");
        ((ObjectNode)reentry.path("startState").path("props").path("bell")).put("holder","actor");
        ((ObjectNode)reentry.path("endState").path("characters").path("actor")).put("lookId","changed-look");
        ((ObjectNode)input.path("assets")).withArray("looks").add(mapper.createObjectNode().put("id","changed-look").put("characterId","actor"));
        ((ObjectNode)reentry.path("referenceViews")).remove("look");((ObjectNode)reentry.path("referenceViews")).put("changed-look","FRONT");
        assertThatThrownBy(()->contract.validate(output,input,"p")).hasMessageContaining("$.shots[2].startState.characters.actor.lookId");
        reentry.withArray("authorizedChanges").add(mapper.createObjectNode().put("path","characters.actor.lookId").put("from","look").put("to","changed-look").put("reason","已确认剧本中老人离画脱下外衣，返回时穿内层衣服").put("atSeconds",1));
        assertThatCode(()->contract.validate(output,input,"p")).doesNotThrowAnyException();
    }
    private ObjectNode shot(int index){return (ObjectNode)output.path("shots").path(index);}
}
