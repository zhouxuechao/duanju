package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import com.yourapp.drama.model.*;
import com.yourapp.drama.production.*;
import com.yourapp.drama.provider.volcengine.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class VolcengineAdaptersTest {
    private HttpServer server;private ObjectMapper mapper;private VolcengineProperties properties;private AtomicReference<JsonNode> imageBody,videoBody;private AtomicReference<String> videoStatus;
    private static final String SIGNED="https://media.example.com/frame.png?X-Signature=a%2Fb+Q%3D&x=1";
    @BeforeEach void start()throws Exception{
        mapper=new ObjectMapper();imageBody=new AtomicReference<>();videoBody=new AtomicReference<>();videoStatus=new AtomicReference<>("succeeded");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/images/generations",exchange->{imageBody.set(read(exchange));send(exchange,200,"{\"created\":1770000000,\"data\":[{\"url\":\""+SIGNED.replace("&","\\u0026")+"\"}]}");});
        server.createContext("/api/v3/contents/generations/tasks",exchange->{String path=exchange.getRequestURI().getPath();if(path.endsWith("/tasks")&&exchange.getRequestMethod().equals("POST")){videoBody.set(read(exchange));send(exchange,200,"{\"id\":\"task-123\"}");}else if(exchange.getRequestMethod().equals("GET")){String status=videoStatus.get();String content=status.equals("succeeded")?",\"content\":{\"video_url\":\"https://media.example.com/result.mp4?sig=x%2By\"}":"";send(exchange,200,"{\"id\":\"task-123\",\"status\":\""+status+"\""+content+"}");}else{send(exchange,200,"{}");}});
        server.start();properties=new VolcengineProperties();properties.setBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v3"));properties.setApiKey("test-only");properties.setTextModel("text-configured");properties.setImageModel("image-configured");properties.setVideoModel("doubao-seedance-2-5-260628");properties.setRequestTimeout(Duration.ofSeconds(3));
    }

    @Test void textGenerationKeepsTheSafeSixteenKDefault(){
        assertThat(new VolcengineProperties().getMaxOutputTokens()).isEqualTo(16384);
    }

    @Test void chatLengthIsReportedAsOutputTruncatedWithProviderDiagnostics(){
        properties.setTextModel("deepseek-v4-pro-ga-260813");properties.setTextApiStyle("chat");
        server.createContext("/api/v3/chat/completions",exchange->{read(exchange);send(exchange,200,mapper.writeValueAsString(Map.of(
                "choices",List.of(Map.of("finish_reason","length","message",Map.of("content","{\"title\":\"unfinished"))),
                "usage",Map.of("prompt_tokens",145,"completion_tokens",16384))));});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
            assertThatThrownBy(()->gateway.generate(new LlmGateway.StructuredRequest("JSON","故事",schema,Map.of()),JsonNode.class))
                    .isInstanceOfSatisfying(ProviderException.class,error->{
                        assertThat(error.code()).isEqualTo("OUTPUT_TRUNCATED");
                        assertThat(error.requestId()).isEqualTo("req-1");
                        assertThat(error.getMessage()).contains("finish_reason=length","input_tokens=145","output_tokens=16384");
                        assertThat(error.rawOutput()).contains("finish_reason","unfinished");
                        assertThat(error.finishReason()).isEqualTo("length");
                        assertThat(error.promptTokens()).isEqualTo(145);
                        assertThat(error.completionTokens()).isEqualTo(16384);
                        assertThat(error.totalTokens()).isEqualTo(16529);
                    });
        }
    }

    @Test void responsesIncompleteMaxTokensIsReportedAsOutputTruncated(){
        properties.setTextModel("doubao-seed-2-0-lite-260428");properties.setTextApiStyle("responses");
        server.createContext("/api/v3/responses",exchange->{read(exchange);send(exchange,200,mapper.writeValueAsString(Map.of(
                "status","incomplete","incomplete_details",Map.of("reason","max_output_tokens"),
                "usage",Map.of("input_tokens",92,"output_tokens",16384),
                "output",List.of(Map.of("content",List.of(Map.of("type","output_text","text","{\"title\":\"unfinished")))))));});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
            assertThatThrownBy(()->gateway.generate(new LlmGateway.StructuredRequest("JSON","故事",schema,Map.of()),JsonNode.class))
                    .isInstanceOfSatisfying(ProviderException.class,error->{
                        assertThat(error.code()).isEqualTo("OUTPUT_TRUNCATED");
                        assertThat(error.getMessage()).contains("finish_reason=max_output_tokens","input_tokens=92","output_tokens=16384");
                        assertThat(error.rawOutput()).contains("incomplete_details","unfinished");
                        assertThat(error.finishReason()).isEqualTo("max_output_tokens");
                        assertThat(error.promptTokens()).isEqualTo(92);
                    });
        }
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void preservesSeedreamUrlAndSendsItAsExactSeedanceFirstFrame(){
        properties.setVideoModel("doubao-seedance-2-5-260628");
        ArkHttpClient client=new ArkHttpClient(mapper,properties);ImageGenerator images=new VolcengineImageGenerator(client,properties);VideoGenerator videos=new VolcengineVideoGenerator(client,properties);
        ImageGenerator.ImageResult image=images.generate(new ImageGenerator.ImageRequest("稳定人物起始姿势",List.of("https://media.example.com/actor.png"),Map.of("seed",42)));
        assertThat(image.providerUrl()).isEqualTo(SIGNED);assertThat(imageBody.get().path("model").asText()).isEqualTo("image-configured");
        assertThat(imageBody.get().path("sequential_image_generation").asText()).isEqualTo("disabled");
        VideoGenerator.Submission task=videos.submit(new VideoGenerator.VideoRequest("总时长3秒，人物转头一次",image.providerUrl(),List.of(),Map.of("duration",3,"ratio","9:16")));
        assertThat(task.taskId()).isEqualTo("task-123");JsonNode content=videoBody.get().path("content");assertThat(content.get(1).path("image_url").path("url").asText()).isEqualTo(SIGNED);assertThat(videoBody.get().toString()).doesNotContain("archive");
        assertThat(videoBody.get().path("duration").asInt()).isEqualTo(5);
        assertThat(videoBody.get().has("ratio")).as("I2V follows the accepted first frame geometry").isFalse();
        assertThat(videos.poll(task.taskId()).providerUrl()).contains("sig=x%2By");
    }
    @Test void defaultPropertiesUseTheTestModelsAndKeepImageAndVideoParametersSeparate(){
        VolcengineProperties defaults=new VolcengineProperties();
        assertThat(defaults.getImageModel()).isEqualTo(ProviderCapabilityRegistry.SEEDREAM_50);
        assertThat(defaults.getVideoModel()).isEqualTo(ProviderCapabilityRegistry.SEEDANCE_20_FAST);
        assertThat(defaults.getVideoResolution()).isEqualTo("480p");
        assertThat(defaults.getImageSize()).isEqualTo("2K");
        var image=new VolcengineImageGenerator(new ArkHttpClient(mapper,properties),properties).requestBodySnapshot(new ImageGenerator.ImageRequest(ProviderCapabilityRegistry.SEEDREAM_50,"测试",List.of(),Map.of()));
        assertThat(image).containsEntry("size",properties.getImageSize()).doesNotContainKeys("resolution","ratio");
    }
    @Test void rejectsReservedOverridesAndOnlyCancelsQueuedTasks(){
        ArkHttpClient client=new ArkHttpClient(mapper,properties);VideoGenerator videos=new VolcengineVideoGenerator(client,properties);
        assertThatThrownBy(()->videos.submit(new VideoGenerator.VideoRequest("动作","https://media.example.com/a.png",List.of(),Map.of("model","attacker")))).isInstanceOf(ProviderException.class).hasMessageContaining("禁止覆盖");
        assertThatThrownBy(()->videos.cancel("task-123")).isInstanceOf(ProviderException.class).hasMessageContaining("排队");videoStatus.set("queued");assertThatCode(()->videos.cancel("task-123")).doesNotThrowAnyException();
    }
    @Test void structuredStoryRequestsBoundOutputAndDisableThinking(){
        AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/responses",exchange->{captured.set(read(exchange));send(exchange,200,"{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"title\\\":\\\"ok\\\"}\"}]}]}");});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
        var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
        var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
        gateway.generate(new LlmGateway.StructuredRequest("JSON","故事",schema,Map.of()),JsonNode.class);
        assertThat(captured.get().path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(captured.get().path("max_output_tokens").asInt()).isEqualTo(16384);
        }
    }
    @Test void deepseekChatUsesJsonModeAndExplicitNonThinkingMode(){
        properties.setTextModel("deepseek-v4-pro-ga-260813");properties.setTextApiStyle("chat");
        AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/chat/completions",exchange->{captured.set(read(exchange));send(exchange,200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"title\\\":\\\"ok\\\"}\"}}]}");});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
            gateway.generate(new LlmGateway.StructuredRequest("JSON","故事",schema,Map.of()),JsonNode.class);
            assertThat(captured.get().path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(captured.get().path("thinking").path("type").asText()).isEqualTo("disabled");
        }
    }
    @Test void directorRequestsUseTheConfiguredDoubaoResponsesModelWhileStoriesStayOnDeepseek(){
        properties.setTextModel("deepseek-v4-pro-ga-260813");properties.setTextApiStyle("chat");
        properties.setDirectorModel("doubao-seed-2-0-lite-260428");properties.setDirectorApiStyle("responses");
        AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/responses",exchange->{captured.set(read(exchange));send(exchange,200,"{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"title\\\":\\\"ok\\\"}\"}]}]}");});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            var schema=Map.<String,Object>of("type","object","properties",Map.of("title",Map.of("type","string")),"required",List.of("title"),"additionalProperties",false);
            var result=gateway.generate(new LlmGateway.StructuredRequest("JSON","导演规划",schema,Map.of("modelRole","director")),JsonNode.class);
            assertThat(result.model()).isEqualTo("doubao-seed-2-0-lite-260428");
            assertThat(captured.get().path("model").asText()).isEqualTo("doubao-seed-2-0-lite-260428");
            JsonNode format=captured.get().path("text").path("format");
            assertThat(format.path("type").asText()).isEqualTo("json_schema");
            assertThat(format.path("strict").asBoolean()).isTrue();
            assertThat(format.path("schema")).isEqualTo(mapper.valueToTree(schema));
        }
    }
    @Test void deepseekJsonModeSendsCompleteStorySchemaInSystemMessage()throws Exception{
        properties.setTextModel("deepseek-v4-pro-ga-260813");properties.setTextApiStyle("chat");
        AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/chat/completions",exchange->{
            captured.set(read(exchange));
            send(exchange,200,mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{\"characters\":[{\"name\":\"阿婆\"}],\"episodes\":[{\"number\":1},{\"number\":2}]}"))))));
        });
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            var schema=storyContractSchema();
            gateway.generate(new LlmGateway.StructuredRequest("你是编剧。","请创作故事。",schema,Map.of()),JsonNode.class);
            JsonNode request=captured.get();
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            JsonNode system=request.path("messages").get(0);
            assertThat(system.path("role").asText()).isEqualTo("system");
            String content=system.path("content").asText();
            String separator="\n\n输出必须符合以下 JSON Schema（保留字段原名和层级，不要增加外层包装）：\n";
            assertThat(content).startsWith("你是编剧。").contains(separator);
            JsonNode transmittedSchema=mapper.readTree(content.substring(content.indexOf(separator)+separator.length()));
            assertThat(transmittedSchema).isEqualTo(mapper.valueToTree(schema));
        }
    }
    @Test void deepseekValidJsonMissingCharactersFailsValidationWithoutResubmission(){
        properties.setTextModel("deepseek-v4-pro-ga-260813");properties.setTextApiStyle("chat");
        AtomicInteger submissions=new AtomicInteger();
        server.createContext("/api/v3/chat/completions",exchange->{
            submissions.incrementAndGet();read(exchange);
            send(exchange,200,mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{\"episodes\":[{\"number\":1},{\"number\":2}]}"))))));
        });
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var gateway=new VolcengineLlmGateway(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()));
            assertThatThrownBy(()->gateway.generate(new LlmGateway.StructuredRequest("你是编剧。","请创作故事。",storyContractSchema(),Map.of()),JsonNode.class))
                    .isInstanceOf(ProviderException.class).hasMessageContaining("$.characters 缺失");
            assertThat(submissions.get()).isEqualTo(1);
        }
    }
    @Test void vlmReviewerSendsGeneratedPixelsAndEveryContinuityReferenceWithStrictSchema()throws Exception{
        properties.setVlmModel("doubao-seed-2-0-lite-260215");AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/responses",exchange->{captured.set(read(exchange));send(exchange,200,mapper.writeValueAsString(Map.of(
            "status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",vlmResult().toString())))))));});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var reviewer=new VolcengineVisualQualityReviewer(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()),mapper,new VisualQualityProtocol());
            ObjectNode expected=mapper.createObjectNode();expected.putObject("requiredConstraints").putArray("characterIdentity").addObject().put("id","actor").put("name","主角");
            expected.withObject("requiredConstraints").putObject("action").put("action","双扇木门全程关闭，人物不得越过门槛");
            ObjectNode composition=expected.withObject("requiredConstraints").putObject("composition");
            composition.putObject("cameraPlan").put("horizontalAngle","相机朝北，南门必须在画外");
            composition.putObject("worldToScreenProjection").put("screenLeft","东").put("screenRight","西").put("nearSide","北").put("farSide","南");
            var refs=expected.putArray("referenceImages");refs.addObject().put("role","CHARACTER_LOOK").put("assetId","actor").put("url","https://media.example.com/actor.png");refs.addObject().put("role","LOCATION").put("assetId","yard").put("url","https://media.example.com/yard.png");refs.addObject().put("role","PROP").put("assetId","bell").put("url","https://media.example.com/bell.png");
            expected.putObject("approvedPreviousKeyframe").put("url","https://media.example.com/previous.png");
            JsonNode result=reviewer.review(expected,mapper.createObjectNode().put("providerUrl",SIGNED));

            JsonNode request=captured.get(),content=request.path("input").path(0).path("content");
            assertThat(request.path("model").asText()).isEqualTo("doubao-seed-2-0-lite-260215");
            assertThat(content.findValuesAsText("image_url")).containsExactly(SIGNED,"https://media.example.com/actor.png","https://media.example.com/yard.png","https://media.example.com/bell.png","https://media.example.com/previous.png");
            assertThat(content.toString()).contains("GENERATED_IMAGE","CHARACTER_LOOK","LOCATION","PROP","PREVIOUS_APPROVED_KEYFRAME");
            String instructions=content.path(0).path("text").asText();
            assertThat(instructions)
                    .contains("逐条引用硬约束中的可观察事实")
                    .contains("固定布局、状态和人物是否越界")
                    .contains("composition.surfaceTopology")
                    .contains("同面、对立、相邻、前后和内外关系")
                    .contains("固定设施被复制、移位、合并、增删")
                    .contains("景别、拍摄方向、主体位置")
                    .contains("必须先使用 worldToScreenProjection 将世界方位投影为画面左右和远近")
                    .contains("\"screenLeft\":\"东\"")
                    .contains("道具持有人、握持方式、尺寸和状态")
                    .contains("每件道具必须先拆分为整体轮廓、主体、连接或握持部件、附件、材质纹理和尺寸比例")
                    .contains("任一可见结构部件与道具参考不符时，propConsistency 必须失败")
                    .contains("composition.interactionGeometry","手臂从与身体投影相反方向进入")
                    .contains("characterIdentity 和 characters[].identity 只评价永久身份外观")
                    .contains("站位、视线、动作或表情错误不得导致 identity 失败")
                    .contains("水印必须最后检查，且不得掩盖其他失败项")
                    .contains("每个独立偏差都必须同时反映在对应 metric 和 failureCodes 中")
                    .contains("双扇木门全程关闭，人物不得越过门槛")
                    .contains("相机朝北，南门必须在画外");
            assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
            assertThat(request.path("text").path("format").path("schema").path("properties").path("characters").path("items").path("properties").path("characterId").path("enum").path(0).asText()).isEqualTo("actor");
            assertThat(result.path("_provider").path("requestId").asText()).isEqualTo("req-1");
        }
    }
    @Test void videoVlmReviewerSendsFiveOrderedTimestampedFrames()throws Exception{
        properties.setVlmModel("doubao-seed-2-0-lite-260215");AtomicReference<JsonNode> captured=new AtomicReference<>();
        server.createContext("/api/v3/responses",exchange->{captured.set(read(exchange));send(exchange,200,mapper.writeValueAsString(Map.of(
            "status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",vlmResult().toString())))))));});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var reviewer=new VolcengineVideoQualityReviewer(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()),new VisualQualityProtocol());
            ObjectNode expected=mapper.createObjectNode();expected.putObject("requiredConstraints").putArray("characterIdentity").addObject().put("id","actor").put("name","主角");
            List<VideoQualityReviewer.Frame> frames=new ArrayList<>();for(int i=0;i<5;i++)frames.add(new VideoQualityReviewer.Frame(i*1.25,"data:image/png;base64,iVBORw0KGgo="));
            JsonNode result=reviewer.review(expected,frames);
            JsonNode content=captured.get().path("input").path(0).path("content");
            assertThat(content.findValuesAsText("image_url")).hasSize(5);
            assertThat(content.toString()).contains("VIDEO_FRAME_1 t=0.000s","VIDEO_FRAME_5 t=5.000s");
            assertThat(result.path("_provider").path("requestId").asText()).isEqualTo("req-1");
        }
    }
    @Test void semanticVlmProtocolFailureKeepsProviderRequestId()throws Exception{
        properties.setVlmModel("doubao-seed-2-0-lite-260428");ObjectNode invalid=vlmResult();invalid.path("characters").get(0).deepCopy();((ObjectNode)invalid.path("characters").get(0)).put("characterId","wrong-actor");
        server.createContext("/api/v3/responses",exchange->{read(exchange);send(exchange,200,mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",invalid.toString())))))));});
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var reviewer=new VolcengineVisualQualityReviewer(new ArkHttpClient(mapper,properties),properties,new StructuredJson(mapper,factory.getValidator()),mapper,new VisualQualityProtocol());ObjectNode expected=mapper.createObjectNode();expected.putObject("requiredConstraints").putArray("characterIdentity").addObject().put("id","actor").put("name","主角");
            assertThatThrownBy(()->reviewer.review(expected,mapper.createObjectNode().put("providerUrl",SIGNED))).isInstanceOf(ProviderException.class).satisfies(error->assertThat(((ProviderException)error).requestId()).isEqualTo("req-1"));
        }
    }
    private Map<String,Object> storyContractSchema(){
        return Map.of("type","object","properties",Map.of(
                "characters",Map.of("type","array","minItems",1,"maxItems",4,"items",Map.of("type","object","properties",Map.of("name",Map.of("type","string","minLength",1)),"required",List.of("name"),"additionalProperties",false)),
                "episodes",Map.of("type","array","minItems",2,"maxItems",2,"items",Map.of("type","object","properties",Map.of("number",Map.of("type","integer","minimum",1,"maximum",2)),"required",List.of("number"),"additionalProperties",false))),
                "required",List.of("characters","episodes"),"additionalProperties",false);
    }
    private ObjectNode vlmResult(){ObjectNode r=mapper.createObjectNode();for(String name:VisualQualityProtocol.METRICS)r.set(name,mapper.createObjectNode().put("score",96).put("pass",true).put("confidence",.96).put("reason","符合").put("evidence","当前画面可见要素与参考一致"));r.putArray("characters").addObject().put("characterId","actor").put("characterName","主角").set("identity",mapper.createObjectNode().put("score",97).put("pass",true).put("confidence",.98).put("reason","同一角色").put("evidence","脸部核心特征与参考一致"));r.putArray("failureCodes");r.put("overallScore",96).put("overallConfidence",.96).put("decision","PASS").put("failureOriginHint","UNKNOWN").put("reason","全部约束符合");return r;}
    private JsonNode read(HttpExchange exchange)throws java.io.IOException{return mapper.readTree(exchange.getRequestBody());}
    private void send(HttpExchange exchange,int status,String json)throws java.io.IOException{byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.getResponseHeaders().add("x-request-id","req-1");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}
