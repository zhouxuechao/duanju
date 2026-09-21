package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class VolcengineVideoRequestContractTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> captured = new AtomicReference<>();
    private VolcengineVideoGenerator generator;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            captured.set(mapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = "{\"id\":\"cgt-route-test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-request-id", "req-route-test");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        VolcengineProperties properties = new VolcengineProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3"));
        properties.setApiKey("test-only"); properties.setTextModel("text-test"); properties.setImageModel("image-test");
        properties.setVideoModel("doubao-seedance-2-5-test");
        properties.setRequestTimeout(Duration.ofSeconds(3));
        generator = new VolcengineVideoGenerator(new ArkHttpClient(mapper, properties), properties);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void fullModalSendsPreviousTakeAsReferenceVideoAndNoFirstFrame() {
        generator.submit(new VideoGenerator.VideoRequest("continue", null, List.of(
                new VideoGenerator.Reference("video_url", "https://media.example.com/previous.mp4", "reference_video"),
                new VideoGenerator.Reference("image_url", "https://media.example.com/keyframe.png", "reference_image")
        ), Map.of("duration", 5)));

        JsonNode content = captured.get().path("content");
        assertThat(content.toString()).contains("reference_video", "previous.mp4", "reference_image", "keyframe.png");
        assertThat(content.toString()).doesNotContain("first_frame");
    }

    @Test void firstFrameRouteDoesNotSendReferenceVideo() {
        VideoGenerator.VideoRequest request=new VideoGenerator.VideoRequest("cut", "https://media.example.com/keyframe.png", List.of(), Map.of("duration", 5));
        JsonNode requestSnapshot=mapper.valueToTree(generator.requestBodySnapshot(request));
        generator.submit(request);
        JsonNode content = captured.get().path("content");
        assertThat(content.toString()).contains("first_frame", "keyframe.png");
        assertThat(content.toString()).doesNotContain("reference_video");
        assertThat(requestSnapshot).isEqualTo(captured.get());
    }

    @Test void adapterRejectsModelProfileHardReferenceLimitBeforeHttpSubmission() {
        java.util.ArrayList<VideoGenerator.Reference> refs=new java.util.ArrayList<>();
        for(int i=0;i<30;i++)refs.add(new VideoGenerator.Reference("image_url","https://media.example.com/ref-"+i+".png","reference_image"));
        assertThatThrownBy(()->generator.submit(new VideoGenerator.VideoRequest("cut","https://media.example.com/start.png",refs,Map.of("duration",5))))
                .isInstanceOf(com.yourapp.drama.model.ProviderException.class).hasMessageContaining("REFERENCE_LIMIT_EXCEEDED");
        assertThat(captured.get()).isNull();
    }
}
