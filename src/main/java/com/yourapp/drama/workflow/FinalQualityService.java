package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class FinalQualityService {
    public ObjectNode evaluate(JsonNode timeline,JsonNode media){ArrayNode failures=JsonNodeFactory.instance.arrayNode(),details=JsonNodeFactory.instance.arrayNode();JsonNode export=timeline.path("exportProfile");long expectedDuration=timeline.path("durationMs").asLong(),actualDuration=media.path("actualDurationMs").asLong();int expectedWidth=export.path("width").asInt(1080),expectedHeight=export.path("height").asInt(1920),actualWidth=media.path("width").asInt(),actualHeight=media.path("height").asInt();double expectedFps=export.path("fps").asDouble(30),actualFps=media.path("frameRate").asDouble();
        if(actualDuration<=0||Math.abs(actualDuration-expectedDuration)>Math.max(250,Math.round(expectedDuration*.02)))failure(failures,details,"FINAL_DURATION_DRIFT","终片时长与锁定时间线偏差超过允许范围");
        if(actualWidth!=expectedWidth||actualHeight!=expectedHeight)failure(failures,details,"FINAL_CANVAS_MISMATCH","终片分辨率与导出规格不一致");
        if(actualFps<=0||Math.abs(actualFps-expectedFps)>.05)failure(failures,details,"FINAL_FRAME_RATE_MISMATCH","终片帧率与导出规格不一致");
        if(media.path("videoCodec").asText().isBlank())failure(failures,details,"FINAL_VIDEO_CODEC_MISSING","终片缺少可识别的视频编码");
        if(timeline.path("cueCount").asInt()>0&&!media.path("hasAudio").asBoolean())failure(failures,details,"FINAL_AUDIO_MISSING","终片包含对白但没有音轨");
        if(media.path("hasAudio").asBoolean()&&media.path("audioCodec").asText().isBlank())failure(failures,details,"FINAL_AUDIO_CODEC_MISSING","终片音轨编码无法识别");
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("passed",failures.isEmpty()).put("checkedAt",Instant.now().toString()).put("expectedDurationMs",expectedDuration).put("actualDurationMs",actualDuration);result.set("failureCodes",failures);result.set("details",details);result.set("mediaMetadata",media.deepCopy());return result;
    }
    private void failure(ArrayNode codes,ArrayNode details,String code,String message){codes.add(code);details.add(JsonNodeFactory.instance.objectNode().put("code",code).put("message",message));}
}
