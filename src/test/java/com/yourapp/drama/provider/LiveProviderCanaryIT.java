package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.model.voice.VoiceGenerator;
import com.yourapp.drama.provider.audio.SeedAudioProperties;
import com.yourapp.drama.provider.audio.SeedAudioVoiceGenerator;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineImageGenerator;
import com.yourapp.drama.provider.volcengine.VolcengineLlmGateway;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Opt-in billable smoke test. Surefire does not discover *IT unless e2e.ps1 selects it explicitly. */
class LiveProviderCanaryIT {
    @Test void textImageVideoAndVoiceReachTheRealProviders() throws Exception {
        assertThat(env("DRAMA_TEST_RUN")).isEqualTo("true");
        ObjectMapper mapper=new ObjectMapper();
        VolcengineProperties properties=new VolcengineProperties();
        properties.setApiKey(required("ARK_API_KEY"));
        properties.setBaseUrl(URI.create(optional("ARK_BASE_URL","https://ark.cn-beijing.volces.com/api/v3")));
        properties.setTextModel(required("ARK_TEXT_MODEL"));
        properties.setImageModel(required("ARK_IMAGE_MODEL"));
        properties.setVideoModel(required("ARK_VIDEO_MODEL"));
        properties.setTextApiStyle(optional("ARK_TEXT_API_STYLE","responses"));
        properties.setRequestTimeout(Duration.ofMinutes(10));
        properties.validate();
        ArkHttpClient client=new ArkHttpClient(mapper,properties);

        String llmRequestId;
        try(var validators=Validation.buildDefaultValidatorFactory()){
            LlmGateway llm=new VolcengineLlmGateway(client,properties,new StructuredJson(mapper,validators.getValidator()));
            Map<String,Object> schema=Map.of("type","object","properties",Map.of("ok",Map.of("type","boolean")),"required",List.of("ok"),"additionalProperties",false);
            LlmGateway.StructuredResult<JsonNode> text=llm.generate(new LlmGateway.StructuredRequest("只输出符合结构的 JSON。","返回 {\"ok\":true}，用于低成本接口连通性检查。",schema,Map.of()),JsonNode.class);
            assertThat(text.value().path("ok").asBoolean()).isTrue();
            llmRequestId=text.requestId();
        }

        ImageGenerator.ImageResult image=new VolcengineImageGenerator(client,properties).generate(new ImageGenerator.ImageRequest(
                "竖屏真人短剧测试画面：白色摄影棚内一只红色马克杯放在木桌中央，固定机位，无文字，无人物。",List.of(),Map.of("watermark",false)));
        assertThat(image.providerUrl()).startsWith("https://");

        VideoGenerator video=new VolcengineVideoGenerator(client,properties);
        VideoGenerator.Submission submission=video.submit(new VideoGenerator.VideoRequest(
                "固定机位，红色马克杯保持外观不变，镜头内只有轻微自然光变化，总时长约4秒，无文字，无声音。",
                image.providerUrl(),List.of(),Map.of("duration",4,"resolution","720p","watermark",false,"generate_audio",false)));
        assertThat(submission.taskId()).isNotBlank();
        VideoGenerator.VideoTask completed=waitFor(video,submission.taskId());
        assertThat(completed.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);
        assertThat(completed.providerUrl()).startsWith("https://");

        SeedAudioProperties audioProperties=new SeedAudioProperties();
        audioProperties.setApiKey(required("SEED_AUDIO_API_KEY"));
        audioProperties.setModel(optional("SEED_AUDIO_MODEL","seed-audio-1.0"));
        String reference=Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("test-fixtures/e2e/golden-basic/mock-provider/voice.wav")));
        VoiceGenerator.VoiceResult voice=new SeedAudioVoiceGenerator(audioProperties,mapper).generate(new VoiceGenerator.VoiceRequest(
                "canary","接口测试通过。","","普通话",1,Map.of("referenceAudioData",reference)));
        assertThat(voice.content()).isNotEmpty();
        assertThat(voice.durationSeconds()).isPositive();

        ObjectNode result=mapper.createObjectNode().put("status","PASS").put("executedAt",Instant.now().toString())
                .put("textRequestId",safe(llmRequestId)).put("imageRequestId",safe(image.requestId()))
                .put("videoTaskId",safe(submission.taskId())).put("videoRequestId",safe(completed.requestId()))
                .put("voiceRequestId",safe(voice.requestId())).put("voiceDurationSeconds",voice.durationSeconds());
        Files.writeString(Path.of("target/live-canary-result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private VideoGenerator.VideoTask waitFor(VideoGenerator generator,String taskId) throws InterruptedException {
        Instant deadline=Instant.now().plus(Duration.ofMinutes(12));
        while(Instant.now().isBefore(deadline)){
            VideoGenerator.VideoTask task=generator.poll(taskId);
            if(task.status()==VideoGenerator.Status.SUCCEEDED)return task;
            if(task.status()==VideoGenerator.Status.FAILED||task.status()==VideoGenerator.Status.CANCELLED||task.status()==VideoGenerator.Status.EXPIRED)
                fail("视频 Canary 失败；taskId="+taskId+" requestId="+safe(task.requestId())+" code="+safe(task.errorCode())+" message="+safe(task.errorMessage()));
            Thread.sleep(5_000);
        }
        return fail("视频 Canary 超时；taskId="+taskId+"，请核对服务商记录，禁止重复提交");
    }
    private String required(String name){String value=env(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少 "+name);return value;}
    private String optional(String name,String fallback){String value=env(name);return value==null||value.isBlank()?fallback:value;}
    private String env(String name){return System.getenv(name);}
    private String safe(String value){return value==null?"":value.replaceAll("[\\r\\n\\t]"," ").substring(0,Math.min(value.length(),200));}
}
