package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FinalQualityServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final FinalQualityService service=new FinalQualityService();
    @Test void acceptsNormalizedPlayableMaster(){ObjectNode timeline=mapper.createObjectNode().put("durationMs",3000).put("cueCount",1);timeline.set("exportProfile",mapper.createObjectNode().put("width",1080).put("height",1920).put("fps",30).put("videoCodec","libx264").put("audioCodec","aac"));ObjectNode media=mapper.createObjectNode().put("actualDurationMs",3030).put("width",1080).put("height",1920).put("frameRate",30).put("videoCodec","h264").put("hasAudio",true).put("audioCodec","aac");ObjectNode qa=service.evaluate(timeline,media);assertThat(qa.path("passed").asBoolean()).isTrue();assertThat(qa.path("failureCodes")).isEmpty();}
    @Test void rejectsDurationDriftWrongCanvasAndMissingAudio(){ObjectNode timeline=mapper.createObjectNode().put("durationMs",3000).put("cueCount",1);timeline.set("exportProfile",mapper.createObjectNode().put("width",1080).put("height",1920).put("fps",30));ObjectNode media=mapper.createObjectNode().put("actualDurationMs",5200).put("width",1920).put("height",1080).put("frameRate",24).put("videoCodec","h264").put("hasAudio",false);ObjectNode qa=service.evaluate(timeline,media);assertThat(qa.path("passed").asBoolean()).isFalse();assertThat(qa.path("failureCodes").toString()).contains("FINAL_DURATION_DRIFT","FINAL_CANVAS_MISMATCH","FINAL_FRAME_RATE_MISMATCH","FINAL_AUDIO_MISSING");}
}
