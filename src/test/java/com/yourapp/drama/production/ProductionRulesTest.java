package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ProductionRulesTest {
    private ObjectMapper mapper;private ProductionService service;
    @BeforeEach void setup(){mapper=new ObjectMapper();ContinuityEngine continuity=new ContinuityEngine(mapper);service=new ProductionService(mapper,continuity,new PromptCompiler(mapper,continuity),new ProductionStateMachine(mapper));}
    @Test void unknownDialectNeverInventsAndSubtitleUsesDisplayText(){
        ObjectNode dialect=mapper.createObjectNode().put("semanticText","你今天跑哪里去了？").put("subtitleText","你今天跑哪里去了？").put("dialect","LEIYANG").put("dialectStrength",1);dialect.putArray("knowledgeBase");JsonNode result=service.renderDialect(dialect);
        assertThat(result.path("needsHumanCorrection").asBoolean()).isTrue();assertThat(result.path("spokenText").asText()).isEmpty();
        ObjectNode subtitle=mapper.createObjectNode();subtitle.putArray("dialogues").add(mapper.createObjectNode().put("dialogueId","1").put("semanticText","标准语义").put("subtitleText","普通话字幕").put("spokenText","谐音口播").put("startMs",0).put("audioDurationMs",1200));
        assertThat(service.subtitle(subtitle).path("srt").asText()).contains("普通话字幕").doesNotContain("谐音口播");
    }
    @Test void approvedCorrectionCreatesThreeTracks(){ObjectNode request=mapper.createObjectNode().put("semanticText","原文").put("subtitleText","原文").put("dialect","LEIYANG");request.set("correction",mapper.createObjectNode().put("approved",true).put("spokenText","发音文本").put("entryId","e1"));JsonNode out=service.renderDialect(request);assertThat(out.path("status").asText()).isEqualTo("HUMAN_CORRECTED");assertThat(out.path("semanticText").asText()).isEqualTo("原文");}
    @Test void timelineUsesArgumentListAndAbsoluteOutput(@TempDir Path temp)throws Exception{Path video=Files.write(temp.resolve("v.mp4"),new byte[]{1});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR").put("quality","PREVIEW");request.putArray("items").add(mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("durationMs",3000).put("locked",true).put("qcPassed",true));JsonNode plan=service.planTimeline(request);assertThat(plan.path("passed").asBoolean()).isTrue();assertThat(plan.path("arguments").toString()).contains("-filter_complex").doesNotContain("cmd.exe").doesNotContain("powershell");}
    @Test void timelineNormalizesVideoAndUsesControlledCrossDissolve(@TempDir Path temp)throws Exception{
        Path first=Files.write(temp.resolve("one.mp4"),new byte[]{1}),second=Files.write(temp.resolve("two.mp4"),new byte[]{2});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR").put("quality","FINAL");request.set("exportProfile",mapper.createObjectNode().put("width",1080).put("height",1920).put("fps",30).put("pixelFormat","yuv420p"));ArrayNode items=request.putArray("items");items.add(mapper.createObjectNode().put("track","VIDEO").put("path",first.toString()).put("startMs",0).put("durationMs",3000).put("transition","CUT").put("qcPassed",true));items.add(mapper.createObjectNode().put("track","VIDEO").put("path",second.toString()).put("startMs",2700).put("durationMs",3000).put("transition","CROSS_DISSOLVE").put("transitionDurationMs",300).put("qcPassed",true));
        JsonNode plan=service.planTimeline(request);
        assertThat(plan.path("passed").asBoolean()).isTrue();assertThat(plan.path("filterGraph").asText()).contains("scale=1080:1920","fps=30","setsar=1","settb=AVTB","format=yuv420p","xfade=transition=fade:duration=0.3:offset=2.7");
    }
    @Test void timelineDucksBgmFadesSoundAndNormalizesLoudness(@TempDir Path temp)throws Exception{
        Path video=Files.write(temp.resolve("video.mp4"),new byte[]{1}),dialogue=Files.write(temp.resolve("dialogue.wav"),new byte[]{2}),bgm=Files.write(temp.resolve("bgm.wav"),new byte[]{3});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");items.add(mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("durationMs",5000).put("transition","CUT"));items.add(mapper.createObjectNode().put("track","DIALOGUE").put("path",dialogue.toString()).put("startMs",1000).put("durationMs",2000));items.add(mapper.createObjectNode().put("track","BGM").put("path",bgm.toString()).put("startMs",0).put("durationMs",5000).put("fadeInMs",500).put("fadeOutMs",800).put("volume",.18));
        JsonNode plan=service.planTimeline(request);assertThat(plan.path("passed").asBoolean()).isTrue();assertThat(plan.path("filterGraph").asText()).contains("afade=t=in:st=0:d=0.5","afade=t=out:st=4.2:d=0.8","sidechaincompress=", "loudnorm=I=-16:TP=-1.5:LRA=11");
    }
    @Test void timelineAppliesAuditedSafeTtsSpeedReconciliation(@TempDir Path temp)throws Exception{
        Path video=Files.write(temp.resolve("video.mp4"),new byte[]{1}),dialogue=Files.write(temp.resolve("dialogue.wav"),new byte[]{2});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");items.add(mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("durationMs",3000));items.add(mapper.createObjectNode().put("track","DIALOGUE").put("path",dialogue.toString()).put("startMs",0).put("durationMs",2000).put("playbackRate",1.05));
        JsonNode plan=service.planTimeline(request);assertThat(plan.path("passed").asBoolean()).isTrue();assertThat(plan.path("filterGraph").asText()).contains("atrim=start=0.000:duration=2.100","atempo=1.05");
    }
    @Test void jCutMustActuallyCrossTheOwnedShotPictureBoundary(@TempDir Path temp)throws Exception{
        Path first=Files.write(temp.resolve("first.mp4"),new byte[]{1}),second=Files.write(temp.resolve("second.mp4"),new byte[]{2}),dialogue=Files.write(temp.resolve("dialogue.wav"),new byte[]{3});
        ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-1").put("path",first.toString()).put("startMs",0).put("durationMs",3000));
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-2").put("path",second.toString()).put("startMs",3000).put("durationMs",3000));
        ObjectNode falseJCut=mapper.createObjectNode().put("track","DIALOGUE").put("shotId","shot-2").put("path",dialogue.toString()).put("startMs",3000).put("durationMs",1200);falseJCut.putArray("editOperations").add("J_CUT");items.add(falseJCut);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isFalse();
        assertThat(plan.path("risks").toString()).contains("J_CUT_POSITION_INVALID");
    }
    @Test void validJCutAndLCutKeepIndependentAudioTimingInTheFfmpegPlan(@TempDir Path temp)throws Exception{
        Path first=Files.write(temp.resolve("first.mp4"),new byte[]{1}),second=Files.write(temp.resolve("second.mp4"),new byte[]{2}),lead=Files.write(temp.resolve("lead.wav"),new byte[]{3}),tail=Files.write(temp.resolve("tail.wav"),new byte[]{4});
        ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-1").put("path",first.toString()).put("startMs",0).put("durationMs",3000));
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-2").put("path",second.toString()).put("startMs",3000).put("durationMs",3000));
        ObjectNode lCut=mapper.createObjectNode().put("track","DIALOGUE").put("shotId","shot-1").put("path",tail.toString()).put("startMs",2200).put("durationMs",1200);lCut.putArray("editOperations").add("L_CUT");items.add(lCut);
        ObjectNode jCut=mapper.createObjectNode().put("track","SFX").put("shotId","shot-2").put("path",lead.toString()).put("startMs",2600).put("durationMs",800);jCut.putArray("editOperations").add("J_CUT");items.add(jCut);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isTrue();
        assertThat(plan.path("filterGraph").asText()).contains("adelay=2200|2200","adelay=2600|2600");
    }
    @Test void audioBridgeMustActuallyCrossAPictureCut(@TempDir Path temp)throws Exception{
        Path first=Files.write(temp.resolve("first.mp4"),new byte[]{1}),second=Files.write(temp.resolve("second.mp4"),new byte[]{2}),roomTone=Files.write(temp.resolve("room.wav"),new byte[]{3});
        ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-1").put("path",first.toString()).put("startMs",0).put("durationMs",3000));
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-2").put("path",second.toString()).put("startMs",3000).put("durationMs",3000));
        ObjectNode falseBridge=mapper.createObjectNode().put("track","AMBIENCE").put("path",roomTone.toString()).put("startMs",500).put("durationMs",1000);falseBridge.putArray("editOperations").add("AUDIO_BRIDGE");items.add(falseBridge);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isFalse();
        assertThat(plan.path("risks").toString()).contains("AUDIO_BRIDGE_POSITION_INVALID");
    }
    @Test void validAudioBridgeSpansTheCutWithoutMovingItsFfmpegStart(@TempDir Path temp)throws Exception{
        Path first=Files.write(temp.resolve("first.mp4"),new byte[]{1}),second=Files.write(temp.resolve("second.mp4"),new byte[]{2}),roomTone=Files.write(temp.resolve("room.wav"),new byte[]{3});
        ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-1").put("path",first.toString()).put("startMs",0).put("durationMs",3000));
        items.add(mapper.createObjectNode().put("track","VIDEO").put("shotId","shot-2").put("path",second.toString()).put("startMs",3000).put("durationMs",3000));
        ObjectNode bridge=mapper.createObjectNode().put("track","AMBIENCE").put("path",roomTone.toString()).put("startMs",2700).put("durationMs",900);bridge.putArray("editOperations").add("AUDIO_BRIDGE");items.add(bridge);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isTrue();
        assertThat(plan.path("filterGraph").asText()).contains("adelay=2700|2700");
    }
    @Test void dialogueGapMustMatchTheSilenceBeforeTheDialogue(@TempDir Path temp)throws Exception{
        Path video=Files.write(temp.resolve("video.mp4"),new byte[]{1}),firstLine=Files.write(temp.resolve("first.wav"),new byte[]{2}),secondLine=Files.write(temp.resolve("second.wav"),new byte[]{3});
        ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");ArrayNode items=request.putArray("items");
        items.add(mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("durationMs",5000));
        items.add(mapper.createObjectNode().put("track","DIALOGUE").put("path",firstLine.toString()).put("startMs",500).put("durationMs",1000));
        ObjectNode gap=mapper.createObjectNode().put("track","DIALOGUE").put("path",secondLine.toString()).put("startMs",1700).put("durationMs",1000).put("gapBeforeMs",600);gap.putArray("editOperations").add("DIALOGUE_GAP");items.add(gap);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isFalse();
        assertThat(plan.path("risks").toString()).contains("DIALOGUE_GAP_MISMATCH");
    }
    @Test void pauseFreezesTheLastFrameInsteadOfReadingPastTheSourceClip(@TempDir Path temp)throws Exception{
        Path video=Files.write(temp.resolve("video.mp4"),new byte[]{1});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR");
        ObjectNode pause=mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("sourceInMs",0).put("sourceOutMs",2000).put("durationMs",3000).put("pauseDurationMs",1000);pause.putArray("editOperations").add("PAUSE");request.putArray("items").add(pause);

        JsonNode plan=service.planTimeline(request);

        assertThat(plan.path("passed").asBoolean()).isTrue();
        assertThat(plan.path("filterGraph").asText()).contains("trim=start=0.000:duration=2.000","tpad=stop_mode=clone:stop_duration=1").doesNotContain("trim=start=0.000:duration=3.000");
    }
    @Test void subtitleAppliesTwoLineStyleAndReportsUnreadableSpeed(){ObjectNode request=mapper.createObjectNode();request.set("styleProfile",mapper.createObjectNode().put("maxCharsPerLine",8).put("maxLines",2).put("maxCps",8).put("fontSize",54).put("outline",3).put("shadow",1).put("marginV",180));request.putArray("dialogues").add(mapper.createObjectNode().put("dialogueId","d1").put("subtitleText","风从村口吹来，所有窗户同时熄灭").put("startMs",0).put("audioDurationMs",1000));JsonNode result=service.subtitle(request);assertThat(result.path("srt").asText()).contains("\n风从村口吹来，\n所有窗户同时熄灭");assertThat(result.path("passed").asBoolean()).isFalse();assertThat(result.path("issues").toString()).contains("SUBTITLE_READING_SPEED");assertThat(result.path("styleProfile").path("maxLines").asInt()).isEqualTo(2);}
    @Test void editingAgentSchemaPublishesEverySupportedEditorialOperation(){JsonNode schema=service.agentSchema("editing");assertThat(schema.toString()).contains("TRIM","CUT","REACTION_SHOT","INSERT_SHOT","J_CUT","L_CUT","AUDIO_BRIDGE","DIALOGUE_GAP","PAUSE","CLIP_REPLACE");}
    @Test void stateMachineAllowsP1DirectKeyframeButKeepsVideoGate(){ObjectNode direct=mapper.createObjectNode().put("from","PLANNED").put("to","KEYFRAME_GENERATING");direct.set("evidence",mapper.createObjectNode().put("p1DirectKeyframe",true));assertThat(service.transitionShot(direct).path("allowed").asBoolean()).isTrue();ObjectNode video=mapper.createObjectNode().put("from","KEYFRAME_LOCKED").put("to","VIDEO_GENERATING");video.set("evidence",mapper.createObjectNode().put("keyframeLocked",true).put("providerUrlValid",false));assertThatThrownBy(()->service.transitionShot(video)).hasMessageContaining("有效的原始");}
}
