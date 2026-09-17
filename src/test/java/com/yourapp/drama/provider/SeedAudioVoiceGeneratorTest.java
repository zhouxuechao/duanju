package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.model.voice.VoiceGenerator;
import com.yourapp.drama.provider.audio.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class SeedAudioVoiceGeneratorTest {
    private HttpServer server;private SeedAudioVoiceGenerator voice;private ObjectMapper mapper;
    private AtomicReference<JsonNode> submitted;private AtomicReference<String> response,statusCode,apiKey;private AtomicInteger httpStatus,calls;
    @BeforeEach void start()throws Exception{
        mapper=new ObjectMapper();submitted=new AtomicReference<>();response=new AtomicReference<>();statusCode=new AtomicReference<>("20000000");apiKey=new AtomicReference<>();httpStatus=new AtomicInteger(200);calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/tts/create",exchange->{calls.incrementAndGet();submitted.set(mapper.readTree(exchange.getRequestBody()));apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));exchange.getResponseHeaders().set("X-Tt-Logid","server-audio-log-123");exchange.getResponseHeaders().set("X-Api-Status-Code",statusCode.get());byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(httpStatus.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();SeedAudioProperties properties=new SeedAudioProperties();properties.setEndpoint(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3/tts/create"));properties.setApiKey("test-voice-key");properties.setRequestTimeout(Duration.ofSeconds(3));voice=new SeedAudioVoiceGenerator(properties,mapper);
        response.set("{\"audio\":\""+Base64.getEncoder().encodeToString(new byte[100])+"\",\"duration\":2.0,\"original_duration\":3.0}");
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void usesNewApiSpeakerReferenceAndActualProcessedDuration(){
        VoiceGenerator.VoiceResult result=voice.generate(request(1.5));
        assertThat(apiKey.get()).isEqualTo("test-voice-key");assertThat(submitted.get().path("model").asText()).isEqualTo("seed-audio-1.0");
        assertThat(submitted.get().path("references").get(0).path("speaker").asText()).isEqualTo("test-speaker");assertThat(submitted.get().path("text_prompt").asText()).contains("@音频1","别开门");
        assertThat(submitted.get().path("audio_config").path("speech_rate").asInt()).isEqualTo(50);
        assertThat(result.durationSeconds()).isEqualTo(2);assertThat(result.requestId()).isEqualTo("server-audio-log-123");assertThat(result.simulated()).isFalse();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void preservesHeaderErrorAndProviderLogWithoutRepeatingRequest(){
        statusCode.set("45000003");response.set("{\"message\":\"speaker is unavailable\"}");
        assertThatThrownBy(()->voice.generate(request(1))).isInstanceOfSatisfying(ProviderException.class,e->{assertThat(e.requestId()).isEqualTo("server-audio-log-123");assertThat(e.getMessage()).contains("45000003","speaker is unavailable");assertThat(e.uncertain()).isFalse();});
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void nonJsonUpstreamFailureStillIncludesHttpStatusAndLogId(){
        httpStatus.set(502);response.set("Bad gateway");
        assertThatThrownBy(()->voice.generate(request(1))).isInstanceOfSatisfying(ProviderException.class,e->{assertThat(e.requestId()).isEqualTo("server-audio-log-123");assertThat(e.statusCode()).isEqualTo(502);assertThat(e.uncertain()).isTrue();});
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void missingDurationDoesNotBecomeAFabricatedTimelineLength(){
        response.set("{\"audio\":\""+Base64.getEncoder().encodeToString(new byte[100])+"\",\"original_duration\":3.0}");
        assertThatThrownBy(()->voice.generate(request(1))).isInstanceOfSatisfying(ProviderException.class,e->assertThat(e.code()).isEqualTo("AUDIO_DURATION_MISSING"));
    }
    @Test void rejectsNonFiniteSpeedBeforeSubmitting(){assertThatThrownBy(()->voice.generate(request(Double.NaN))).isInstanceOf(ProviderException.class);assertThat(calls.get()).isZero();}
    @Test void sendsApprovedBase64AudioReferenceInsteadOfAProviderSpeakerId(){String reference=Base64.getEncoder().encodeToString(new byte[]{1,2,3,4});VoiceGenerator.VoiceResult result=voice.generate(new VoiceGenerator.VoiceRequest("dialogue","别开门","","普通话",1,Map.of("referenceAudioData",reference)));assertThat(result.durationSeconds()).isEqualTo(2);assertThat(submitted.get().path("references").get(0).path("audio_data").asText()).isEqualTo(reference);assertThat(submitted.get().path("references").get(0).path("speaker").isMissingNode()).isTrue();assertThat(submitted.get().path("text_prompt").asText()).contains("@音频1");}
    @Test void rejectsTwoVoiceReferencesTogether(){assertThatThrownBy(()->voice.generate(new VoiceGenerator.VoiceRequest("dialogue","别开门","provider-voice","普通话",1,Map.of("referenceAudioData","AQI=")))).isInstanceOfSatisfying(ProviderException.class,e->assertThat(e.code()).isEqualTo("VOICE_REFERENCE_REQUIRED"));assertThat(calls.get()).isZero();}
    private VoiceGenerator.VoiceRequest request(double speed){return new VoiceGenerator.VoiceRequest("dialogue","别开门","test-speaker","普通话",speed,Map.of());}
}
