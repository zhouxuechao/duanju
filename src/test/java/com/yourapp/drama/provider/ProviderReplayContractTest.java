package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderReplayContractTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final Map<String,JsonNode> captured=new ConcurrentHashMap<>();
    private HttpServer server;
    private JsonNode llmFixture,imageFixture,submitFixture,pollFixture,audioFixture;

    @BeforeEach void start() throws Exception {
        llmFixture=fixture("llm-responses.json");imageFixture=fixture("seedream.json");submitFixture=fixture("seedance-submit.json");pollFixture=fixture("seedance-poll.json");audioFixture=fixture("seed-audio.json");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/responses",exchange->handle(exchange,"llm",llmFixture,false));
        server.createContext("/api/v3/images/generations",exchange->handle(exchange,"image",imageFixture,false));
        server.createContext("/api/v3/contents/generations/tasks",exchange->{
            if("POST".equals(exchange.getRequestMethod()))handle(exchange,"video-submit",submitFixture,false);
            else handle(exchange,"video-poll",pollFixture,false);
        });
        server.createContext("/api/v3/tts/create",exchange->handle(exchange,"audio",audioFixture,true));
        server.start();
    }

    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void sanitizedProviderRecordsReplayAgainstCurrentAdapters() throws Exception {
        String fixtureText=Files.readString(Path.of("test-fixtures/provider-replay/llm-responses.json"))+Files.readString(Path.of("test-fixtures/provider-replay/seedream.json"))+Files.readString(Path.of("test-fixtures/provider-replay/seedance-submit.json"))+Files.readString(Path.of("test-fixtures/provider-replay/seedance-poll.json"))+Files.readString(Path.of("test-fixtures/provider-replay/seed-audio.json"));
        assertThat(fixtureText).doesNotContain("Bearer ").doesNotContain("ark-").doesNotContain("api_key");

        VolcengineProperties properties=new VolcengineProperties();
        properties.setBaseUrl(URI.create(base()+"/api/v3"));properties.setApiKey("replay-only-key");properties.setTextModel("text-replay");properties.setImageModel("seedream-replay");properties.setVideoModel("seedance-replay");properties.setRequestTimeout(Duration.ofSeconds(3));
        ArkHttpClient http=new ArkHttpClient(mapper,properties);
        try(var validatorFactory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
            var llm=new VolcengineLlmGateway(http,properties,new StructuredJson(mapper,validatorFactory.getValidator()));
            var story=llm.generate(new LlmGateway.StructuredRequest("你是编剧。","写一个一句话故事。",schema,Map.of()),JsonNode.class);
            assertThat(story.value().path("title").asText()).isEqualTo("回放故事");
            assertSubset(captured.get("llm"),llmFixture.path("expectedRequest"));
        }
        ImageGenerator.ImageResult image=new VolcengineImageGenerator(http,properties).generate(new ImageGenerator.ImageRequest("一只铜铃置于木桌正中",List.of(),Map.of("watermark",false)));
        assertThat(image.providerUrl()).isEqualTo("https://media.example.invalid/replay/keyframe.png");
        assertSubset(captured.get("image"),imageFixture.path("expectedRequest"));

        VideoGenerator video=new VolcengineVideoGenerator(http,properties);
        VideoGenerator.Submission submission=video.submit(new VideoGenerator.VideoRequest("铜铃轻微晃动，机位保持固定",null,List.of(new VideoGenerator.Reference("image_url",image.providerUrl(),"reference_image")),Map.of("duration",4,"ratio","9:16")));
        assertThat(submission.taskId()).isEqualTo(pollFixture.path("taskId").asText());
        assertSubset(captured.get("video-submit"),submitFixture.path("expectedRequest"));
        List<String> roles=new java.util.ArrayList<>();captured.get("video-submit").path("content").forEach(n->roles.add(n.path("role").asText(n.path("type").asText())));
        assertThat(roles).containsExactly("text","reference_image");
        VideoGenerator.VideoTask completed=video.poll(submission.taskId());
        assertThat(completed.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);assertThat(completed.providerUrl()).endsWith("/take.mp4");assertThat(completed.requestId()).isEqualTo("video-poll-request-001");

        SeedAudioProperties audioProperties=new SeedAudioProperties();audioProperties.setEndpoint(URI.create(base()+"/api/v3/tts/create"));audioProperties.setApiKey("replay-only-audio-key");audioProperties.setModel("seed-audio-replay");audioProperties.setRequestTimeout(Duration.ofSeconds(3));
        VoiceGenerator.VoiceResult voice=new SeedAudioVoiceGenerator(audioProperties,mapper).generate(new VoiceGenerator.VoiceRequest("replay","别开门。","approved-speaker-replay","普通话",1,"[VOICE IDENTITY]\n采用批准音色。\n[SPEECH CONTENT]\n唯一允许说出的对白：别开门。\n[OUTPUT CONSTRAINTS]\n只输出单人干声。",Map.of()));
        assertThat(voice.requestId()).isEqualTo("audio-replay-request-001");assertThat(voice.durationSeconds()).isEqualTo(1.25);assertThat(voice.content()).hasSize(64);
        JsonNode audio=captured.get("audio");assertThat(audio.path("model").asText()).isEqualTo(audioFixture.path("expectedRequest").path("model").asText());assertThat(audio.path("references").get(0).path("speaker").asText()).isEqualTo(audioFixture.path("expectedRequest").path("speaker").asText());assertThat(audio.path("audio_config").path("sample_rate").asInt()).isEqualTo(48000);
    }

    private void handle(HttpExchange exchange,String key,JsonNode fixture,boolean audio) throws java.io.IOException {
        if(exchange.getRequestBody()!=null&&exchange.getRequestHeaders().containsKey("Content-Type"))captured.put(key,mapper.readTree(exchange.getRequestBody()));
        fixture.path("responseHeaders").fields().forEachRemaining(e->exchange.getResponseHeaders().set(e.getKey(),e.getValue().asText()));
        JsonNode response=fixture.path("response").deepCopy();
        if(audio)((com.fasterxml.jackson.databind.node.ObjectNode)response).put("audio",Base64.getEncoder().encodeToString(new byte[64]));
        byte[] bytes=mapper.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }

    private JsonNode fixture(String name) throws Exception{return mapper.readTree(Path.of("test-fixtures/provider-replay",name).toFile());}
    private String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    private void assertSubset(JsonNode actual,JsonNode expected){expected.fields().forEachRemaining(entry->assertThat(actual.path(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue()));}
}
