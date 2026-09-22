package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.*;

class PromptCompilerV2Test {
    private final ObjectMapper mapper=new ObjectMapper();
    private PromptCompiler compiler;
    @BeforeEach void setUp(){compiler=new PromptCompiler(mapper,new ContinuityEngine(mapper));}

    @Test void keyframeUsesOrderedTaskSpecificSectionsAndSemanticReferenceAuthority(){
        ObjectNode request=request("ESTABLISHING");request.put("imageTaskType","KEYFRAME");
        var result=compiler.compileImage(request);
        assertThat(result.compilerVersion()).startsWith("4.");
        assertInOrder(result.prompt(),"[TASK / IMAGE PURPOSE]","[REFERENCE BINDINGS]","[CHARACTER IDENTITY LOCK]","[CURRENT LOOK / VISIBLE STATE]","[LOCATION IDENTITY / LAYOUT LOCK]","[PROP IDENTITY / HOLDER]","[ACTION / POSE / EYELINE]","[COMPOSITION / BLOCKING]","[CAMERA]","[LIGHTING]","[VISUAL STYLE]","[CONTINUITY LOCK]","[DYNAMIC AVOID]","[OUTPUT]");
        assertThat(result.prompt()).contains("wrong hand","identity drift","worldToScreenProjection").doesNotContain("挥动铜铃三次");
        assertThat(result.references()).allSatisfy(ref->{assertThat(ref.path("referenceId").asText()).isNotBlank();assertThat(ref.path("controls")).isNotEmpty();assertThat(ref.path("mustNotTransfer").isArray()).isTrue();assertThat(ref.path("authorityPriority").isInt()).isTrue();});
    }

    @Test void compilerExposesProviderNeutralPromptIrBeforeRenderingProviderText(){
        ObjectNode request=request("ESTABLISHING");request.put("imageTaskType","KEYFRAME");
        var result=compiler.compileImage(request);
        var ir=result.promptIR();
        assertThat(ir.taskType()).isEqualTo("KEYFRAME");
        assertThat(ir.shotIntent().path("shotId").asText()).isEqualTo("shot-1");
        assertThat(ir.characterConstraints().path("actor").path("identityId").asText()).isEqualTo("actor");
        assertThat(ir.locationConstraints().path("locationId").asText()).isEqualTo("location");
        assertThat(ir.propConstraints().path("bell").path("heldByHand").asText()).isEqualTo("RIGHT");
        assertThat(ir.subject().path("characterIds").get(0).asText()).isEqualTo("actor");
        assertThat(ir.identity().path("actor").path("id").asText()).isEqualTo("actor");
        assertThat(ir.costume().path("actor").path("id").asText()).isEqualTo("look");
        assertThat(ir.environment().path("locationId").asText()).isEqualTo("location");
        assertThat(ir.action().path("currentAction").asText()).isEqualTo("挥动铜铃三次");
        assertThat(ir.emotion().path("intended").asText()).isEqualTo("警觉");
        assertThat(ir.blocking().path("characters")).hasSize(1);
        assertThat(ir.blocking().path("characters").path(0).path("movementVector").asText()).isEqualTo("STATIC");
        assertThat(ir.blocking().path("props").path(0).path("worldPosition").asText()).isEqualTo("胸前");
        assertThat(ir.camera().path("lensMm").asInt()).isEqualTo(50);
        assertThat(ir.lighting().path("direction").asText()).isEqualTo("东窗侧光");
        assertThat(ir.audio().path("generateAudio").asBoolean()).isFalse();
        assertThat(ir.dialogue().path("dialogueIds")).isEmpty();
        assertThat(ir.continuity().path("startState").path("locationId").asText()).isEqualTo("location");
        assertThat(ir.negativeConstraints()).contains("identity drift").anyMatch(value->value.startsWith("wrong hand"));
        assertThat(ir.output().path("ratio").asText()).isEqualTo("9:16");
        assertThat(result.prompt()).contains("[TASK / IMAGE PURPOSE]","[OUTPUT]");
    }

    @Test void assetReferenceAndLipSyncAlsoUseTheFormalPromptIrPipeline(){
        ObjectNode asset=obj().put("assetKind","LOCATION").put("assetId","location-1").put("view","FRONT")
                .put("compilerVersion","4.2.0-location-topology").put("revisionFeedback","保持门窗位置");
        asset.set("sourceSnapshot",obj().set("asset",obj().put("id","location-1").put("name","旧祠堂")
                .set("locationBible",obj().put("layout","门在西墙，供桌在东墙"))));
        asset.set("viewCamera",LocationViewProjection.describe("FRONT"));
        asset.putArray("referenceViewIds").add("layout-view-1");
        asset.putArray("referenceBindings").add(obj().put("referenceId","layout-view-1").put("role","LOCATION_TOPOLOGY_REFERENCE"));

        var assetPrompt=compiler.compileAssetReference(asset);
        assertThat(assetPrompt.compilerVersion()).isEqualTo("4.2.0-location-topology");
        assertThat(assetPrompt.modelFamily()).isEqualTo("SEEDREAM");
        assertThat(assetPrompt.promptIR().taskType()).isEqualTo("ASSET_REFERENCE");
        assertThat(assetPrompt.promptIR().camera().path("view").asText()).isEqualTo("FRONT");
        assertThat(assetPrompt.prompt()).startsWith("[TARGET VIEW — HIGHEST PRIORITY]").contains("[REFERENCE AUTHORITY]");

        ObjectNode lipSync=obj().put("shotId","shot-1").put("sourceTakeId","take-1").put("videoUrl","https://example.com/take.mp4");
        lipSync.putArray("audioClipIds").add("clip-1");
        lipSync.putArray("references")
                .add(obj().put("type","video_url").put("url","https://example.com/take.mp4").put("role","reference_video"))
                .add(obj().put("type","audio_url").put("url","https://example.com/line.mp3").put("role","reference_audio"));

        var lipSyncPrompt=compiler.compileLipSync(lipSync);
        assertThat(lipSyncPrompt.compilerVersion()).isEqualTo("4.0.0-lipsync");
        assertThat(lipSyncPrompt.modelFamily()).isEqualTo("SEEDANCE");
        assertThat(lipSyncPrompt.promptIR().taskType()).isEqualTo("LIPSYNC");
        assertThat(lipSyncPrompt.promptIR().audio().path("audioClipIds").get(0).asText()).isEqualTo("clip-1");
        assertThat(lipSyncPrompt.prompt()).contains("[REFERENCE AUTHORITY]","[CURRENT ACTION / ENDPOINT]","[HIGH-RISK CONTINUITY LOCKS]");
    }

    @Test void voiceBusinessInstructionsAreCompiledBeforeTheProviderAdapter(){
        ObjectNode request=obj().put("dialogueId","dialogue-1").put("semanticText","不要打开那扇门")
                .put("spokenText","莫开那扇门").put("subtitleText","别开那扇门").put("dialect","LEIYANG")
                .put("speed",.95).put("voiceProfileId","voice-1").put("voiceReferenceMode","PROVIDER_VOICE_ID");

        var result=compiler.compileVoice(request);

        assertThat(result.compilerVersion()).isEqualTo("4.0.0-voice");
        assertThat(result.modelFamily()).isEqualTo("SEED_AUDIO");
        assertThat(result.promptIR().taskType()).isEqualTo("VOICE");
        assertThat(result.promptIR().dialogue().path("spokenText").asText()).isEqualTo("莫开那扇门");
        assertThat(result.promptIR().dialogue().path("semanticText").asText()).isEqualTo("不要打开那扇门");
        assertThat(result.prompt()).contains("[SPEECH CONTENT]","@音频1","唯一允许说出的对白：莫开那扇门").doesNotContain("不要打开那扇门","别开那扇门");
    }

    @Test void directorAndStoryStructuredRequestsUseTheSameAuditedCompilerEntry(){
        ObjectNode input=obj().put("phase","DIRECTOR_PLAN").put("documentId","doc-1");
        ObjectNode schema=obj().put("type","object").set("properties",obj().set("shots",obj().put("type","array")));

        var director=compiler.compileDirector("DIRECTOR_PLAN",input,schema,"只选择当前场景需要的空间与表演规则");
        assertThat(director.compilerVersion()).isEqualTo("4.0.0-director");
        assertThat(director.modelFamily()).isEqualTo("ARK_STRUCTURED_TEXT");
        assertThat(director.promptIR().taskType()).isEqualTo("DIRECTOR_PLAN");
        assertThat(director.systemPrompt()).contains("[SKILL CONTRACT]","[RUNTIME RULES AND STAGE]","当前阶段：DIRECTOR_PLAN","只选择当前场景需要的空间与表演规则");
        assertThat(director.userPrompt()).isEqualTo(input.toString());
        assertThat(director.schema()).isEqualTo(schema);

        var story=compiler.compileStory("PREMISE",input,schema);
        assertThat(story.compilerVersion()).isEqualTo("4.0.0-story");
        assertThat(story.promptIR().taskType()).isEqualTo("PREMISE");
        assertThat(story.systemPrompt()).contains("[SKILL CONTRACT]");
    }

    @Test void sequencePromptCarriesOnlyCurrentDeltaAndProtectsCompletedAndFutureBeats(){
        ObjectNode request=request("CONTINUOUS"),shot=(ObjectNode)request.path("shot");
        shot.put("feltIntent","让观众先于人物察觉门外有人").put("endpoint","人物停在门边，铜铃仍在右手");
        shot.putArray("completedBeats").add("OPEN_DOOR");shot.putArray("reservedFutureBeats").add("IDENTITY_REVEAL");
        shot.set("promptCarriers",obj().putArray("performance").add("人物听到门外脚步后短暂停顿"));
        ObjectNode change=obj().put("path","characters.actor.actionState").put("atSeconds",2.4).put("reason","脚步声接近");
        change.set("from",obj().put("action","LISTEN").put("progress",0.2).put("hand","RIGHT").put("object","bell"));
        change.set("to",obj().put("action","LISTEN").put("progress",0.8).put("hand","RIGHT").put("object","bell"));
        shot.putArray("stateChanges").add(change);
        request.set("previousTake",obj().put("id","take-1").put("locked",true).put("selected",true).put("qcPassed",true).put("continuationDepth",0).put("videoUrl","https://example.com/take.mp4").set("observedState",request.path("previousState").deepCopy()));
        request.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2).put("resetAtSceneBoundary",true));
        var result=compiler.compileVideo(request);
        assertInOrder(result.prompt(),"[SHOT INTENT / CARRIERS]","[REFERENCE AUTHORITY]","[SOURCE / LINEAGE]","[ACTUAL OPENING STATE]","[CURRENT ACTION / ENDPOINT]","[TIMED BEATS]","[CAMERA / MOTION PHASE]","[PHYSICS / INTERACTION]","[TIMED STATE TRANSITIONS]","[HIGH-RISK CONTINUITY LOCKS]","[COMPLETED BEAT EXCLUSIONS]","[RESERVED FUTURE BEAT EXCLUSIONS]","[DYNAMIC AVOID]","[AUDIO POLICY]","[OUTPUT CONSTRAINTS]");
        assertThat(result.prompt()).contains("take-1","OPEN_DOOR","IDENTITY_REVEAL","2.4","generate_audio=false","铜铃仍在右手","\"action\":\"LISTEN\"","\"progress\":0.8").doesNotContain("future-secret");
        assertThat(result.prompt().length()).isLessThanOrEqualTo(10_000);
    }

    @Test void boundaryGateRejectsRepeatedCompletedBeatAndRequiresReanchorAtChainLimit(){
        ObjectNode repeated=request("CONTINUOUS"),shot=(ObjectNode)repeated.path("shot");videoFields(shot);shot.put("action","OPEN_DOOR");shot.putArray("completedBeats").add("OPEN_DOOR");
        repeated.set("previousTake",acceptedTake(1,repeated.path("previousState")));repeated.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2));
        assertThatThrownBy(()->compiler.compileVideo(repeated)).hasMessageContaining("COMPLETED_BEAT_REPEATED");

        ObjectNode chain=request("CONTINUOUS"),chainShot=(ObjectNode)chain.path("shot");videoFields(chainShot);chain.set("previousTake",acceptedTake(2,chain.path("previousState")));chain.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2));
        assertThatThrownBy(()->compiler.compileVideo(chain)).hasMessageContaining("REANCHOR_REQUIRED");
        chain.set("reanchorPlan",obj().put("reason","达到连续生成深度上限").put("useCanonicalReferences",true));
        assertThat(compiler.compileVideo(chain).strategy()).isEqualTo(ProductionModels.Strategy.REANCHOR_AFTER_DRIFT);
    }

    @Test void boundaryGateRejectsCurrentBeatMarkedCompletedEvenWhenActionTextDiffers(){
        ObjectNode request=request("CONTINUOUS"),shot=(ObjectNode)request.path("shot");videoFields(shot);
        shot.set("currentBeat",obj().put("beatId","beat-2").put("beatPurpose","人物发现异常"));
        shot.putArray("completedBeats").add("beat-2");
        request.set("previousTake",acceptedTake(0,request.path("previousState")));request.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2));
        assertThatThrownBy(()->compiler.compileVideo(request)).hasMessageContaining("COMPLETED_BEAT_REPEATED");
    }

    @Test void continuousPromptUsesOnlyVisibleStateInsteadOfAllOffscreenHistory(){
        ObjectNode request=request("CONTINUOUS"),shot=(ObjectNode)request.path("shot");videoFields(shot);
        ObjectNode previous=(ObjectNode)request.path("previousState");
        for(int i=0;i<80;i++){ObjectNode offscreen=previous.withObject("characters").putObject("offscreen-"+i).put("identityId","offscreen-"+i).put("lookId","look-offscreen");offscreen.set("actionState",obj().put("action","OFFSCREEN_HISTORY_"+i).put("progress",1).put("hand","NONE").put("object",""));}
        request.set("previousTake",acceptedTake(0,previous));request.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2));
        var result=compiler.compileVideo(request);
        assertThat(result.prompt()).doesNotContain("offscreen-79","不应进入当前提示词的历史状态标记").contains("actor","bell");
    }

    @Test void continuousRequestRejectsAPlannedOpeningThatDoesNotMatchAcceptedObservedEnd(){
        ObjectNode request=request("CONTINUOUS"),shot=(ObjectNode)request.path("shot");videoFields(shot);
        ObjectNode observed=(ObjectNode)request.path("previousState").deepCopy();observed.withObject("characters").withObject("actor").put("position","门外西侧");
        request.set("previousTake",acceptedTake(0,observed));request.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",2));
        assertThatThrownBy(()->compiler.compileVideo(request)).hasMessageContaining("PREVIOUS_OBSERVED_STATE_MISMATCH");
    }

    private void videoFields(ObjectNode shot){shot.put("feltIntent","让观众注意人物的迟疑").put("endpoint","人物停下且道具仍在右手");shot.set("promptCarriers",obj().putArray("performance").add("动作结束前停顿半秒"));shot.putArray("completedBeats");shot.putArray("reservedFutureBeats");}
    private ObjectNode acceptedTake(int depth,com.fasterxml.jackson.databind.JsonNode state){ObjectNode take=obj().put("id","take-prev").put("locked",true).put("selected",true).put("qcPassed",true).put("continuationDepth",depth).put("videoUrl","https://example.com/prev.mp4");take.set("observedState",state.deepCopy());return take;}
    private void assertInOrder(String text,String... values){int previous=-1;for(String value:values){int at=text.indexOf(value);assertThat(at).as(value).isGreaterThan(previous);previous=at;}}

    private ObjectNode request(String relation){
        ObjectNode r=obj(),shot=r.putObject("shot").put("shotId","shot-1").put("duration",5).put("purpose","发现门外异响").put("action","挥动铜铃三次").put("shotSize","MEDIUM").put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("locationId","location").put("relationToPrevious",relation).put("difficulty","B").put("visualFocus","握铃的右手").put("emotion","警觉");
        shot.putArray("characterIds").add("actor");shot.putArray("propIds").add("bell");shot.putArray("dialogueIds");
        shot.set("cameraPlan",obj().put("position","门内东侧").put("height","胸口高度").put("distance","3米").put("lensMm",50).put("horizontalAngle","朝西").put("verticalAngle","水平").put("subjectPlacement","画面左侧").put("focusPoint","右手铜铃").put("depthOfField","中等").put("lightingDirection","东窗侧光").put("movementPath","固定").put("movementSpeed","无"));
        ObjectNode blocking=obj().put("cameraWorldPosition","人物东侧3米").put("axis","人物—西墙门轴线").put("axisSide","A_SIDE").put("axisChangeReason","").put("keyObjectPositions","铜铃在人物右手，门在西墙").put("doorWindowState","西墙门关闭");
        blocking.putArray("characters").add(obj().put("characterId","actor").put("worldPosition","门内东侧").put("facing","朝西").put("movementVector","STATIC").put("screenDirection","STATIC").put("framePosition","画面左侧").put("eyeLineTarget","门缝").put("visibleBodyPart","WHOLE_BODY").put("bodyFrameSide","IN_FRAME_LEFT").put("limbEntrySide","LEFT").put("contactPoint","右手掌与铃柄接触"));
        blocking.putArray("props").add(obj().put("propId","bell").put("worldPosition","胸前"));blocking.putArray("spatialAnchors");shot.set("blocking",blocking);
        shot.set("performancePlan",obj().put("primaryAction","人物握紧铜铃后停住").put("microExpression","眼神转向门外").put("bodyLanguage","重心后移").put("gaze","门缝").put("gesture","右手握铃").put("actionUnits",1).putArray("propOperations").add("右手握铃"));
        shot.set("visibilityPlan",obj().put("occlusion","NONE").put("requiredDetail","PROP_DETAIL").putArray("visibleFeatures").add("铃柄与右手接触点"));
        shot.set("referenceViews",obj().put("look","FRONT").put("location","FRONT").put("bell","FRONT"));
        ObjectNode state=obj().put("locationId","location").put("time","夜").put("lighting","东窗冷光").put("spatialRelations","门在西墙，供桌在对面东墙");
        ObjectNode actorState=state.putObject("characters").putObject("actor").put("identityId","actor").put("lookId","look").put("rightHandProp","bell").put("holding","bell").put("position","门内东侧").put("lookDirection","朝西").put("pose","站立");
        actorState.set("actionState",obj().put("action","ALERT").put("progress",1).put("hand","RIGHT").put("object","bell"));
        state.putObject("props").putObject("bell").put("holder","actor").put("heldByHand","RIGHT").put("position","胸前").put("state","完好");shot.set("startState",state);shot.set("endState",state.deepCopy());r.set("previousState",state.deepCopy());
        ObjectNode assets=r.putObject("assets").put("style","低照度乡村夜景，东窗冷光与油灯暖光方向固定");assets.putArray("characters").add(obj().put("id","actor").put("name","守夜人").set("identityTraits",obj().put("face","长脸、左眉疤痕").put("body","微驼")));
        ObjectNode look=assets.putArray("looks").addObject().put("id","look").put("characterId","actor").put("name","守夜服").put("description","深蓝棉衣，灰布腰带");
        ObjectNode location=assets.putArray("locations").addObject().put("id","location").put("name","祠堂").put("description","门在西墙，供桌与牌位在对面东墙").set("locationBible",obj().put("layout","门与供桌位于相对墙面").put("spatialAnchors","东窗、西墙门、东墙供桌").put("lighting","东窗冷光，西侧油灯暖光"));
        ObjectNode prop=assets.putArray("props").addObject().put("id","bell").put("name","铜铃").put("description","木柄铜铃").set("propBible",obj().put("appearance","圆柱木柄连接铜铃顶部环扣").put("scale","手掌大小").put("ownership","守夜人"));views(look,false);views(location,true);views(prop,false);
        r.set("keyframe",obj().put("id","keyframe-1").put("version",1).put("locked",true).put("providerUrl","https://example.com/keyframe.png"));
        r.set("providerCapabilities",obj().put("version","fixture-v1").put("supportsNativeAudio",false).put("maxImageRefs",10));r.set("videoOutputProfile",obj().put("ratio","9:16").put("resolution","1080p"));return r;
    }
    private void views(ObjectNode asset,boolean location){ArrayNode views=asset.putArray("approvedViews");String[] names=location?new String[]{"FRONT","SIDE","REVERSE","LAYOUT"}:new String[]{"FRONT","SIDE","BACK","DETAIL"};for(String name:names)views.add(obj().put("id",asset.path("id").asText()+name).put("view",name).put("setVersion",1).put("approved",true).put("providerUrl","https://example.com/"+name+".png"));}
}
