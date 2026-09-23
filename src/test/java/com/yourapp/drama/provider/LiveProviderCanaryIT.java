package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.production.ProviderCapabilityContract;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineImageGenerator;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import com.yourapp.drama.workflow.LiveCanaryPlan;
import com.yourapp.drama.workflow.LiveCanaryState;
import com.yourapp.drama.workflow.MediaProbeService;
import com.yourapp.drama.workflow.TestBudgetGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Explicitly enabled, billable Phase A canary: exactly one image and one video submission. */
class LiveProviderCanaryIT {
    private static final String IMAGE_PROMPT="一名年轻男子站在现代客厅窗边，竖屏构图，人物全身，自然光，背景整洁，主体清晰，无文字。";
    private static final String VIDEO_PROMPT="人物站在窗边，缓慢转头看向镜头，保持人物身份、服装、背景和机位稳定，动作自然，不要切镜，不要改变场景，无文字，无声音。";

    @Test
    @EnabledIfEnvironmentVariable(named="RUN_LIVE_PROVIDER_CANARY",matches="(?i)true")
    void seedreamToSeedanceProducesGranularEvidenceWithoutAutomaticRetry() throws Exception {
        ObjectMapper mapper=new ObjectMapper();String runId=optional("DRAMA_TEST_RUN_ID","provider-canary-"+UUID.randomUUID());
        ObjectNode evidence=mapper.createObjectNode().put("provider","VOLCENGINE").put("evidenceSource","LIVE_CANARY")
                .put("phase","PROVIDER").put("generationProfile","TEST").put("runId",runId).put("status","RUNNING");
        ObjectNode preflight=LiveCanaryPlan.provider().validateLive(System.getenv(),List.of());
        assertThat(preflight.path("ready").asBoolean()).as(preflight.path("blocking").toString()).isTrue();
        VolcengineProperties properties=properties();
        ProviderCapabilityRegistry capabilities=new ProviderCapabilityRegistry(properties.getVideoModel(),properties.getImageModel(),properties.getVideoResolution(),properties.getImageSize(),properties.getVideoMinDuration(),properties.getVideoMaxDuration(),properties.getVideoDurationStep());
        LiveCanaryState state=LiveCanaryState.start(Path.of("target","live-canary","provider-canary-state.json"),runId);
        try{
            ArkHttpClient client=new ArkHttpClient(mapper,properties);
            TestBudgetGuard budget=budget(runId);ArrayNode ledger=evidence.putArray("paidRequests");

            double canaryCostLimit=decimal("TEST_MAX_COST_CNY",5),defaultReservation=canaryCostLimit/2;
            ObjectNode imageBudget=budgetInput(mapper,runId,decimal("TEST_IMAGE_ESTIMATED_COST_CNY",defaultReservation));budget.reserve("KEYFRAME",imageBudget);
            String imageLocalJobId="canary-image-"+UUID.randomUUID(),imageSubmittedAt=Instant.now().toString();ImageGenerator.ImageResult image;
            state.advance("IMAGE_SUBMITTING","","");
            try{image=new VolcengineImageGenerator(client,properties).generate(new ImageGenerator.ImageRequest(properties.getImageModel(),IMAGE_PROMPT,List.of(),Map.of("watermark",false,"size","2K")));budget.settle("KEYFRAME",imageBudget,false);state.advance("IMAGE_SUCCEEDED",image.requestId(),"");}
            catch(RuntimeException failure){budget.settle("KEYFRAME",imageBudget,true);stateFailure(state,failure,"","");throw failure;}
            String imageCompletedAt=Instant.now().toString();assertThat(image.requestId()).isNotBlank();assertThat(image.providerUrl()).startsWith("https://");
            byte[] imageBytes=download(image.providerUrl());Path mediaDir=Path.of("target","live-canary",runId);Files.createDirectories(mediaDir);Files.write(mediaDir.resolve("keyframe"+imageSuffix(imageBytes)),imageBytes);
            BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(imageBytes));assertThat(decoded).as("Seedream 返回图片必须可解码并探测尺寸").isNotNull();
            ObjectNode imageEvidence=mapper.createObjectNode().put("modelId",properties.getImageModel()).put("providerRequestId",safe(image.requestId()))
                    .put("submittedAt",imageSubmittedAt).put("completedAt",imageCompletedAt).put("requestedImageQuality","2K")
                    .put("aspectRatioIntent","9:16").put("providerSize","2K").put("providerReturnedWidth",decoded.getWidth())
                    .put("providerReturnedHeight",decoded.getHeight()).put("actualAspectRatio",decoded.getWidth()/(double)decoded.getHeight())
                    .put("providerUrl","[REDACTED]").put("providerUrlHost",URI.create(image.providerUrl()).getHost()).put("providerUrlFingerprint",sha256(image.providerUrl()));
            if(image.expiresAt()!=null)imageEvidence.put("providerUrlExpiresAt",image.expiresAt().toString());evidence.set("image",imageEvidence);
            ledger.add(requestRecord(mapper,imageLocalJobId,"KEYFRAME",properties.getImageModel(),image.requestId(),"","2K",0,0,"SUCCESS"));

            VolcengineVideoGenerator videoGenerator=new VolcengineVideoGenerator(client,properties,capabilities);
            VideoGenerator.VideoRequest videoRequest=new VideoGenerator.VideoRequest(properties.getVideoModel(),VIDEO_PROMPT,image.providerUrl(),List.of(),Map.of("duration",5,"resolution","480p","watermark",false,"generate_audio",false,"return_last_frame",true));
            JsonNode videoRequestBody=mapper.valueToTree(videoGenerator.requestBodySnapshot(videoRequest));String handedOff=firstFrame(videoRequestBody);
            assertThat(handedOff).as("Seedream 原始 URL 必须逐字符进入 Seedance first_frame").isEqualTo(image.providerUrl());
            ObjectNode videoBudget=budgetInput(mapper,runId,decimal("TEST_VIDEO_ESTIMATED_COST_CNY",defaultReservation));budget.reserve("VIDEO",videoBudget);
            String videoLocalJobId="canary-video-"+UUID.randomUUID(),videoSubmittedAt=Instant.now().toString();VideoGenerator.Submission submission;VideoGenerator.VideoTask video;
            state.advance("VIDEO_SUBMITTING",image.requestId(),"");
            try{submission=videoGenerator.submit(videoRequest);state.advance("VIDEO_SUBMITTED",submission.requestId(),submission.taskId());video=waitFor(videoGenerator,submission.taskId());budget.settle("VIDEO",videoBudget,false);}
            catch(RuntimeException|AssertionError failure){budget.settle("VIDEO",videoBudget,true);stateFailure(state,failure,"","");throw failure;}
            String videoCompletedAt=Instant.now().toString();assertThat(video.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);assertThat(submission.requestId()).isNotBlank();assertThat(submission.taskId()).isNotBlank();
            byte[] videoBytes=download(video.providerUrl());Path videoPath=mediaDir.resolve("video.mp4");Files.write(videoPath,videoBytes);
            ObjectNode media;try(var stream=Files.newInputStream(videoPath)){media=new MediaProbeService(mapper,required("FFPROBE_PATH")).probe(stream,".mp4");}
            String firstFrameFingerprint=sha256(handedOff),sourceFingerprint=sha256(image.providerUrl());
            ObjectNode videoEvidence=mapper.createObjectNode().put("modelId",properties.getVideoModel()).put("providerRequestId",safe(submission.requestId()))
                    .put("providerTaskId",safe(submission.taskId())).put("submittedAt",videoSubmittedAt).put("completedAt",videoCompletedAt)
                    .put("requestedDuration",5).put("providerDuration",5).put("requestedResolution","480p")
                    .put("actualWidth",media.path("width").asInt()).put("actualHeight",media.path("height").asInt())
                    .put("actualDurationMs",media.path("actualDurationMs").asLong()).put("frameRate",media.path("frameRate").asDouble()).put("ratio","9:16")
                    .put("actualAspectRatio",media.path("width").asInt()/(double)media.path("height").asInt())
                    .put("hasAudio",media.path("hasAudio").asBoolean()).put("generateAudio",false).put("referenceMode","FIRST_FRAME")
                    .put("firstFrameSource","SEEDREAM_PROVIDER_URL").put("firstFrameFingerprint",firstFrameFingerprint)
                    .put("sourceImageFingerprint",sourceFingerprint).put("providerUrlHandoff",handedOff.equals(image.providerUrl()))
                    .put("providerStatus",video.status().name()).put("providerUrl","[REDACTED]").put("providerUrlHost",URI.create(video.providerUrl()).getHost()).put("providerUrlFingerprint",sha256(video.providerUrl()));
            if(video.expiresAt()!=null)videoEvidence.put("providerUrlExpiresAt",video.expiresAt().toString());evidence.set("video",videoEvidence);
            ledger.add(requestRecord(mapper,videoLocalJobId,"VIDEO",properties.getVideoModel(),submission.requestId(),submission.taskId(),"480p",5,media.path("actualDurationMs").asLong(),"SUCCESS"));

            evidence.put("checkedAt",Instant.now().toString()).put("status","PASS").put("retryCount",0).put("billingStatus","UNPRICED");evidence.set("budget",budget.snapshot(runId));
            ObjectNode snapshot=new ProviderCapabilityContract().verifyCanary(capabilities.imageProfile(properties.getImageModel()),capabilities.profile(properties.getVideoModel()),evidence);
            evidence.set("capabilitySnapshot",snapshot);writeArtifacts(mapper,evidence,snapshot);
            assertThat(snapshot.path("failures")).isEmpty();assertThat(snapshot.path("verificationStatus").asText()).isEqualTo("PARTIAL_LIVE_VERIFIED");state.advance("SUCCEEDED",submission.requestId(),submission.taskId());
        }catch(Throwable failure){
            stateFailure(state,failure,"","");
            evidence.put("checkedAt",Instant.now().toString()).put("status","FAILED");evidence.set("failure",mapper.createObjectNode().put("category",category(failure)).put("message",sanitize(failure.getMessage())));writeFailureArtifacts(mapper,evidence);
            if(failure instanceof Exception exception)throw exception;if(failure instanceof Error error)throw error;throw new RuntimeException(failure);
        }
    }

    private VolcengineProperties properties(){VolcengineProperties value=new VolcengineProperties();value.setApiKey(required("ARK_API_KEY"));value.setBaseUrl(URI.create(optional("ARK_BASE_URL","https://ark.cn-beijing.volces.com/api/v3")));value.setTextModel(optional("ARK_TEXT_MODEL","unused-provider-canary"));value.setImageModel(optional("ARK_IMAGE_MODEL",ProviderCapabilityRegistry.SEEDREAM_50));value.setImageSize(optional("ARK_IMAGE_SIZE","2K"));value.setVideoModel(optional("ARK_VIDEO_MODEL",ProviderCapabilityRegistry.SEEDANCE_20_FAST));value.setVideoResolution(optional("ARK_VIDEO_RESOLUTION","480p"));value.setRequestTimeout(Duration.ofMinutes(10));value.validate();return value;}
    private TestBudgetGuard budget(String runId){return new TestBudgetGuard(new MockEnvironment().withProperty("drama.provider.mode","volcengine").withProperty("DRAMA_TEST_RUN","true").withProperty("DRAMA_TEST_RUN_ID",runId).withProperty("TEST_MAX_COST_CNY",optional("TEST_MAX_COST_CNY","5")).withProperty("TEST_MAX_REAL_IMAGE_REQUESTS","1").withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","1").withProperty("TEST_MAX_REAL_AUDIO_REQUESTS","0"));}
    private ObjectNode budgetInput(ObjectMapper mapper,String run,double cost){return mapper.createObjectNode().put("testRun",true).put("testRunId",run).put("estimatedCost",cost);}
    private ObjectNode requestRecord(ObjectMapper mapper,String localJobId,String taskType,String model,String requestId,String taskId,String size,int requestedDuration,long actualDuration,String status){return mapper.createObjectNode().put("localJobId",localJobId).put("taskType",taskType).put("model",model).put("generationProfile","TEST").put("providerRequestId",safe(requestId)).put("providerTaskId",safe(taskId)).put(taskType.equals("VIDEO")?"resolution":"imageSize",size).put("requestedDuration",requestedDuration).put("actualDurationMs",actualDuration).put("status",status).put("retryCount",0).put("billingStatus","UNPRICED");}
    private VideoGenerator.VideoTask waitFor(VideoGenerator generator,String taskId) throws InterruptedException{Instant deadline=Instant.now().plus(Duration.ofMinutes(12));while(Instant.now().isBefore(deadline)){VideoGenerator.VideoTask task=generator.poll(taskId);if(task.status()==VideoGenerator.Status.SUCCEEDED)return task;if(task.status()==VideoGenerator.Status.FAILED||task.status()==VideoGenerator.Status.CANCELLED||task.status()==VideoGenerator.Status.EXPIRED)fail("视频 Canary 失败；taskId="+safe(taskId)+" requestId="+safe(task.requestId())+" code="+safe(task.errorCode())+" message="+sanitize(task.errorMessage()));Thread.sleep(5_000);}return fail("视频 Canary 超时；taskId="+safe(taskId)+"，请核对服务商记录，禁止重复提交");}
    private byte[] download(String url) throws Exception{HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();HttpResponse<byte[]> response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request,HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isBetween(200,299);assertThat(response.body().length).isGreaterThan(1024);return response.body();}
    private String firstFrame(JsonNode body){for(JsonNode item:body.path("content"))if("first_frame".equals(item.path("role").asText()))return item.path("image_url").path("url").asText();return "";}
    private void writeArtifacts(ObjectMapper mapper,ObjectNode evidence,ObjectNode snapshot) throws Exception{Path target=Path.of("target","live-canary");Files.createDirectories(target);mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-canary-evidence.json").toFile(),evidence);mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","provider-capability-snapshot.json").toFile(),snapshot);writeReport(evidence);}
    private void writeFailureArtifacts(ObjectMapper mapper,ObjectNode evidence){try{Path target=Path.of("target","live-canary");Files.createDirectories(target);mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-canary-evidence.json").toFile(),evidence);writeReport(evidence);}catch(Exception ignored){}}
    private void writeReport(JsonNode evidence) throws Exception{Path directory=Path.of("docs","canary");Files.createDirectories(directory);String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());JsonNode image=evidence.path("image"),video=evidence.path("video"),failure=evidence.path("failure");String report="# Provider Canary "+timestamp+"\n\n- 状态：`"+evidence.path("status").asText()+"`\n- 档位：`TEST`\n- 图片模型：`"+image.path("modelId").asText(ProviderCapabilityRegistry.SEEDREAM_50)+"`\n- 图片请求 ID：`"+image.path("providerRequestId").asText("PENDING")+"`\n- 图片实际尺寸：`"+image.path("providerReturnedWidth").asText("PENDING")+" × "+image.path("providerReturnedHeight").asText("PENDING")+"`\n- 视频模型：`"+video.path("modelId").asText(ProviderCapabilityRegistry.SEEDANCE_20_FAST)+"`\n- 视频请求/任务 ID：`"+video.path("providerRequestId").asText("PENDING")+"` / `"+video.path("providerTaskId").asText("PENDING")+"`\n- 视频实际尺寸：`"+video.path("actualWidth").asText("PENDING")+" × "+video.path("actualHeight").asText("PENDING")+"`\n- 实际时长：`"+video.path("actualDurationMs").asText("PENDING")+" ms`\n- 原始 URL handoff：`"+video.path("providerUrlHandoff").asText("PENDING")+"`\n- 成本：`UNPRICED`\n- 重试次数：`0`\n- 失败分类：`"+failure.path("category").asText("NONE")+"`\n- 失败信息："+failure.path("message").asText("无")+"\n";Files.writeString(directory.resolve("provider-canary-"+timestamp+".md"),report,StandardCharsets.UTF_8);}
    private String imageSuffix(byte[] bytes){return bytes.length>8&&bytes[0]==(byte)0x89&&bytes[1]==0x50?".png":".jpg";}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少 "+name);return value;}
    private String optional(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    private double decimal(String name,double fallback){try{return Double.parseDouble(optional(name,Double.toString(fallback)));}catch(RuntimeException error){throw new IllegalStateException(name+" 必须是数值");}}
    private String safe(String value){if(value==null)return "";String clean=value.replaceAll("[\\r\\n\\t]"," ");return clean.substring(0,Math.min(clean.length(),200));}
    private void stateFailure(LiveCanaryState state,Throwable failure,String requestId,String taskId){try{String current=state.snapshot().path("status").asText();if("RECONCILIATION_REQUIRED".equals(current)||"FAILED".equals(current)||"SUCCEEDED".equals(current))return;boolean uncertain=failure instanceof ProviderException provider&&provider.uncertain()||"VIDEO_SUBMITTED".equals(current)||String.valueOf(failure.getMessage()).toUpperCase().contains("TIMEOUT");String providerRequestId=failure instanceof ProviderException provider?safe(provider.requestId()):safe(requestId);state.advance(uncertain?"RECONCILIATION_REQUIRED":"FAILED",providerRequestId,taskId);}catch(RuntimeException ignored){}}
    private String sanitize(String value){if(value==null)return "模型调用失败";return value.replaceAll("ark-[A-Za-z0-9-]{10,}","[REDACTED]").replaceAll("https?://\\S+","[REDACTED_URL]").replaceAll("(?i)(bearer\\s+)[^\\s]+","$1[REDACTED]");}
    private String category(Throwable failure){String name=failure.getClass().getSimpleName().toUpperCase(),message=String.valueOf(failure.getMessage()).toUpperCase();if(message.contains("AUTH")||message.contains("401")||message.contains("403"))return "PROVIDER_CAPABILITY";if(message.contains("UNKNOWN")||message.contains("TIMEOUT"))return "UNKNOWN";if(message.contains("NETWORK")||name.contains("IO"))return "NETWORK";if(message.contains("RATIO")||message.contains("RESOLUTION")||message.contains("DURATION"))return "PROVIDER_CAPABILITY";return "SYSTEM_BUG";}
}
