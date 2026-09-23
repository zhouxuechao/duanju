package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveProviderCanaryRequestContractTest {
    @Test void phaseAVideoRequestUsesOnlyMinimalOptionsAndPreservesFirstFrameExactly() throws Exception {
        String canarySource=Files.readString(Path.of("src","test","java","com","yourapp","drama","provider","LiveProviderCanaryIT.java"));
        assertThat(canarySource).doesNotContain("\"return_last_frame\",true", "\"camera_fixed\"", "\"seed\"", "\"priority\"", "\"service_tier\"", "\"draft\"");

        String firstFrame="https://example.invalid/seedream/result.png?token=CaseSensitive%2FValue";
        VolcengineProperties properties=new VolcengineProperties();
        properties.setApiKey("test-only-placeholder");
        properties.setTextModel("test-only-placeholder");
        properties.setImageModel("doubao-seedream-5-0-260128");
        properties.setVideoModel("doubao-seedance-2-0-fast-260128");
        properties.setVideoResolution("480p");
        ObjectMapper mapper=new ObjectMapper();
        VolcengineVideoGenerator generator=new VolcengineVideoGenerator(new ArkHttpClient(mapper,properties),properties);
        VideoGenerator.VideoRequest request=new VideoGenerator.VideoRequest(properties.getVideoModel(),"人物缓慢转头",firstFrame,List.of(),
                Map.of("duration",5,"resolution","480p","watermark",false,"generate_audio",false));

        JsonNode body=mapper.valueToTree(generator.requestBodySnapshot(request));
        assertThat(body.path("duration").asInt()).isEqualTo(5);
        assertThat(body.path("resolution").asText()).isEqualTo("480p");
        assertThat(body.path("watermark").asBoolean()).isFalse();
        assertThat(body.path("generate_audio").asBoolean()).isFalse();
        assertThat(body.has("return_last_frame")).isFalse();
        assertThat(body.has("camera_fixed")).isFalse();
        assertThat(body.has("seed")).isFalse();
        assertThat(body.has("priority")).isFalse();
        assertThat(body.has("service_tier")).isFalse();
        assertThat(body.has("draft")).isFalse();
        assertThat(firstFrame(body)).isEqualTo(firstFrame);
    }

    private String firstFrame(JsonNode body){
        for(JsonNode item:body.path("content"))if("first_frame".equals(item.path("role").asText()))return item.path("image_url").path("url").asText();
        return "";
    }

    @Test void providerSuccessIsPersistedBeforeLocalMediaValidation() throws Exception {
        String source=Files.readString(Path.of("src","test","java","com","yourapp","drama","provider","LiveProviderCanaryIT.java"));
        int wait=source.indexOf("video=waitFor(videoGenerator,submission.taskId())");
        int succeeded=source.indexOf("state.advance(\"VIDEO_SUCCEEDED\"");
        int download=source.indexOf("download(video.providerUrl())");
        int capability=source.indexOf("verifyCanary(");

        assertThat(wait).isGreaterThanOrEqualTo(0);
        assertThat(succeeded).isGreaterThan(wait);
        assertThat(download).isGreaterThan(succeeded);
        assertThat(capability).isGreaterThan(download);
    }
}
