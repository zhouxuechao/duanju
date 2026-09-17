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
        ObjectNode dialect=mapper.createObjectNode().put("displayText","你今天跑哪里去了？").put("dialect","LEIYANG").put("dialectStrength",1);dialect.putArray("knowledgeBase");JsonNode result=service.renderDialect(dialect);
        assertThat(result.path("needsHumanCorrection").asBoolean()).isTrue();assertThat(result.path("speechText").asText()).isEmpty();
        ObjectNode subtitle=mapper.createObjectNode();subtitle.putArray("dialogues").add(mapper.createObjectNode().put("dialogueId","1").put("displayText","普通话字幕").put("speechText","谐音口播").put("startMs",0).put("audioDurationMs",1200));
        assertThat(service.subtitle(subtitle).path("srt").asText()).contains("普通话字幕").doesNotContain("谐音口播");
    }
    @Test void approvedCorrectionCreatesThreeTracks(){ObjectNode request=mapper.createObjectNode().put("displayText","原文").put("dialect","LEIYANG");request.set("correction",mapper.createObjectNode().put("approved",true).put("dialectText","方言表达").put("speechText","发音文本").put("entryId","e1"));JsonNode out=service.renderDialect(request);assertThat(out.path("status").asText()).isEqualTo("HUMAN_CORRECTED");assertThat(out.path("displayText").asText()).isEqualTo("原文");}
    @Test void timelineUsesArgumentListAndAbsoluteOutput(@TempDir Path temp)throws Exception{Path video=Files.write(temp.resolve("v.mp4"),new byte[]{1});ObjectNode request=mapper.createObjectNode().put("outputPath",temp.resolve("out.mp4").toString()).put("subtitleMode","SIDECAR").put("quality","PREVIEW");request.putArray("items").add(mapper.createObjectNode().put("track","VIDEO").put("path",video.toString()).put("startMs",0).put("durationMs",3000).put("locked",true).put("qcPassed",true));JsonNode plan=service.planTimeline(request);assertThat(plan.path("passed").asBoolean()).isTrue();assertThat(plan.path("arguments").toString()).contains("-filter_complex").doesNotContain("cmd.exe").doesNotContain("powershell");}
    @Test void stateMachineAllowsP1DirectKeyframeButKeepsVideoGate(){ObjectNode direct=mapper.createObjectNode().put("from","PLANNED").put("to","KEYFRAME_GENERATING");direct.set("evidence",mapper.createObjectNode().put("p1DirectKeyframe",true));assertThat(service.transitionShot(direct).path("allowed").asBoolean()).isTrue();ObjectNode video=mapper.createObjectNode().put("from","KEYFRAME_LOCKED").put("to","VIDEO_GENERATING");video.set("evidence",mapper.createObjectNode().put("keyframeLocked",true).put("providerUrlValid",false));assertThatThrownBy(()->service.transitionShot(video)).hasMessageContaining("有效的原始");}
}
