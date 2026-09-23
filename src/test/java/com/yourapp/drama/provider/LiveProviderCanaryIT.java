package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineImageGenerator;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import com.yourapp.drama.workflow.TestBudgetGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Opt-in billable three-shot continuity/re-anchor canary. Surefire only runs it through e2e.ps1 Canary. */
class LiveProviderCanaryIT {
    private static final double IMAGE_COST=.20;
    private static final double VIDEO_COST=1.50;

    @Test void firstFrameAndContinuousShotReachTheRealProviderWithPreviousTakeReference() throws Exception {
        assertThat(env("DRAMA_TEST_RUN")).isEqualTo("true");
        String runId=required("DRAMA_TEST_RUN_ID");
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
        TestBudgetGuard budget=new TestBudgetGuard(new MockEnvironment().withProperty("drama.provider.mode","volcengine")
                .withProperty("DRAMA_TEST_RUN","true").withProperty("DRAMA_TEST_RUN_ID",runId)
                .withProperty("TEST_MAX_COST_CNY",optional("TEST_MAX_COST_CNY","5"))
                .withProperty("TEST_MAX_REAL_IMAGE_REQUESTS","1").withProperty("TEST_MAX_REAL_VIDEO_REQUESTS","3"));
        ObjectNode imageBudget=budgetInput(mapper,runId,IMAGE_COST);budget.reserve("KEYFRAME",imageBudget);

        String imagePrompt="竖屏真人短剧连续性测试首帧。中国北方村庄旧祠堂前的青石院，夜晚冷月光；一位约六十五岁的中国老妇人，短灰发，深蓝色棉袄、黑布裤、黑布鞋，站在木门右侧半米处；她右手握住一只带缺口的黄铜手摇铃木柄，铃口垂直向下，左手自然下垂。门与供桌位于相对墙面，门上没有牌位、匾额或装饰。写实真人，人物全身可见，固定身份、服装、道具结构与空间关系，无文字。";
        ImageGenerator.ImageResult image;
        try {
            image=new VolcengineImageGenerator(client,properties).generate(new ImageGenerator.ImageRequest(imagePrompt,List.of(),Map.of("watermark",false,"size","2K")));
            budget.settle("KEYFRAME",imageBudget,false);
        } catch(RuntimeException failure) { budget.settle("KEYFRAME",imageBudget,true);throw failure; }
        assertThat(image.providerUrl()).startsWith("https://");

        VolcengineVideoGenerator video=new VolcengineVideoGenerator(client,properties);
        ObjectNode shot1Budget=budgetInput(mapper,runId,VIDEO_COST);budget.reserve("VIDEO",shot1Budget);
        String shot1Prompt="首镜，固定中全景。保持老妇人的脸、短灰发、深蓝棉袄、站位、木门和缺口黄铜铃完全来自首帧。她只用右手把铃铛缓慢抬到胸口，铃口始终向下，左手不动；镜头不切换，无新增人物、牌位、匾额和道具，无文字，无声音。";
        VideoGenerator.VideoRequest shot1Request=new VideoGenerator.VideoRequest(shot1Prompt,image.providerUrl(),List.of(),videoOptions());
        JsonNode shot1Body=mapper.valueToTree(video.requestBodySnapshot(shot1Request));
        VideoGenerator.Submission shot1Submission;
        VideoGenerator.VideoTask shot1;
        try {
            shot1Submission=video.submit(shot1Request);shot1=waitFor(video,shot1Submission.taskId());budget.settle("VIDEO",shot1Budget,false);
        } catch(RuntimeException failure) { budget.settle("VIDEO",shot1Budget,true);throw failure; }
        assertThat(shot1.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);

        ObjectNode shot2Budget=budgetInput(mapper,runId,VIDEO_COST);budget.reserve("VIDEO",shot2Budget);
        String shot2Prompt="连续镜头，动作必须从参考视频末尾无跳变接续。保持同一老妇人的脸、短灰发、深蓝棉袄、右手持同一只缺口黄铜铃、站位和祠堂空间不变；她接着轻摇铃铛一次后停住，铃口仍向下，左手不换边。固定机位，无新增人物、牌位、匾额和道具，无文字，无声音。";
        List<VideoGenerator.Reference> shot2References=List.of(
                new VideoGenerator.Reference("video_url",shot1.providerUrl(),"reference_video"),
                new VideoGenerator.Reference("image_url",image.providerUrl(),"reference_image"));
        VideoGenerator.VideoRequest shot2Request=new VideoGenerator.VideoRequest(shot2Prompt,null,shot2References,videoOptions());
        JsonNode shot2Body=mapper.valueToTree(video.requestBodySnapshot(shot2Request));
        assertThat(shot2Request.firstFrameUrl()).isNull();
        assertThat(shot2Request.references()).extracting(VideoGenerator.Reference::role).containsExactly("reference_video","reference_image");
        VideoGenerator.Submission shot2Submission;
        VideoGenerator.VideoTask shot2;
        try {
            shot2Submission=video.submit(shot2Request);shot2=waitFor(video,shot2Submission.taskId());budget.settle("VIDEO",shot2Budget,false);
        } catch(RuntimeException failure) { budget.settle("VIDEO",shot2Budget,true);throw failure; }
        assertThat(shot2.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);

        ObjectNode shot3Budget=budgetInput(mapper,runId,VIDEO_COST);budget.reserve("VIDEO",shot3Budget);
        String shot3Prompt="重新锚定镜头，固定近景。只采用首帧中同一老妇人的脸、短灰发、深蓝棉袄与同一只木柄缺口黄铜铃；保持她在木门右侧半米处，门与供桌仍在相对墙面。她右手把铃铛稳定停在胸口，铃口向下，左手自然下垂；无新增人物、牌位、匾额和道具，无文字，无声音。";
        VideoGenerator.VideoRequest shot3Request=new VideoGenerator.VideoRequest(shot3Prompt,image.providerUrl(),List.of(),videoOptions());
        JsonNode shot3Body=mapper.valueToTree(video.requestBodySnapshot(shot3Request));
        VideoGenerator.Submission shot3Submission;
        VideoGenerator.VideoTask shot3;
        try {
            shot3Submission=video.submit(shot3Request);shot3=waitFor(video,shot3Submission.taskId());budget.settle("VIDEO",shot3Budget,false);
        } catch(RuntimeException failure) { budget.settle("VIDEO",shot3Budget,true);throw failure; }
        assertThat(shot3.status()).isEqualTo(VideoGenerator.Status.SUCCEEDED);

        Path directory=Path.of("target/live-canary");Files.createDirectories(directory);
        archive(image.providerUrl(),directory.resolve("keyframe.png"));
        archive(shot1.providerUrl(),directory.resolve("shot-01.mp4"));
        archive(shot2.providerUrl(),directory.resolve("shot-02.mp4"));
        archive(shot3.providerUrl(),directory.resolve("shot-03.mp4"));
        Files.writeString(directory.resolve("shot-01-request.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shot1Body));
        Files.writeString(directory.resolve("shot-02-request.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shot2Body));
        Files.writeString(directory.resolve("shot-03-request.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(shot3Body));
        assertThat(Files.size(directory.resolve("keyframe.png"))).isGreaterThan(1024);
        assertThat(Files.size(directory.resolve("shot-01.mp4"))).isGreaterThan(1024);
        assertThat(Files.size(directory.resolve("shot-02.mp4"))).isGreaterThan(1024);
        assertThat(Files.size(directory.resolve("shot-03.mp4"))).isGreaterThan(1024);

        ObjectNode result=mapper.createObjectNode().put("status","TECHNICAL_PASS_VISUAL_REVIEW_PENDING").put("executedAt",Instant.now().toString())
                .put("testRunId",runId).put("imageRequestId",safe(image.requestId())).put("imageTaskId","SYNC_NO_TASK_ID")
                .put("imageProviderUrl",image.providerUrl()).put("previousTakeId",safe(shot1Submission.taskId())).put("continuationDepth",1)
                .put("observedState","PENDING_VISUAL_INSPECTION").put("plannedCost",IMAGE_COST+3*VIDEO_COST)
                .put("actualCost",IMAGE_COST+3*VIDEO_COST).put("wasteCost",0).put("costBasis","ESTIMATED_PROVIDER_RATE");
        result.set("budget",budget.snapshot(runId));
        ArrayNode shots=result.putArray("shots");
        shots.add(shot(mapper,1,"FIRST_FRAME",shot1Prompt,shot1Body,shot1Submission,shot1,image.providerUrl(),false,false));
        shots.add(shot(mapper,2,"FULL_MODAL_REFERENCE",shot2Prompt,shot2Body,shot2Submission,shot2,null,true,true));
        shots.add(shot(mapper,3,"REANCHOR_FIRST_FRAME",shot3Prompt,shot3Body,shot3Submission,shot3,image.providerUrl(),false,false));
        result.set("qcResult",mapper.createObjectNode().put("technicalMedia","PASS").put("visualContinuity","PENDING"));
        Files.writeString(Path.of("target/live-canary-result.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private ObjectNode shot(ObjectMapper mapper,int number,String route,String prompt,JsonNode requestBody,VideoGenerator.Submission submission,VideoGenerator.VideoTask task,String firstFrame,boolean referenceImage,boolean referenceVideo){ObjectNode shot=mapper.createObjectNode().put("shotNo",number).put("videoRequestRoute",route).put("prompt",prompt).put("taskId",safe(submission.taskId())).put("submitRequestId",safe(submission.requestId())).put("pollRequestId",safe(task.requestId())).put("providerUrl",task.providerUrl()).put("first_frame",firstFrame==null?"":firstFrame).put("reference_image",referenceImage).put("reference_video",referenceVideo);shot.set("requestBody",requestBody.deepCopy());return shot;}
    private ObjectNode budgetInput(ObjectMapper mapper,String run,double cost){return mapper.createObjectNode().put("testRun",true).put("testRunId",run).put("estimatedCost",cost);}
    private Map<String,Object> videoOptions(){return Map.of("duration",4,"resolution","480p","watermark",false,"generate_audio",false,"return_last_frame",true);}
    private void archive(String url,Path path) throws Exception {HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();HttpResponse<Path> response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request,HttpResponse.BodyHandlers.ofFile(path));assertThat(response.statusCode()).isBetween(200,299);}
    private VideoGenerator.VideoTask waitFor(VideoGenerator generator,String taskId) throws InterruptedException {Instant deadline=Instant.now().plus(Duration.ofMinutes(12));while(Instant.now().isBefore(deadline)){VideoGenerator.VideoTask task=generator.poll(taskId);if(task.status()==VideoGenerator.Status.SUCCEEDED)return task;if(task.status()==VideoGenerator.Status.FAILED||task.status()==VideoGenerator.Status.CANCELLED||task.status()==VideoGenerator.Status.EXPIRED)fail("视频 Canary 失败；taskId="+taskId+" requestId="+safe(task.requestId())+" code="+safe(task.errorCode())+" message="+safe(task.errorMessage()));Thread.sleep(5_000);}return fail("视频 Canary 超时；taskId="+taskId+"，请核对服务商记录，禁止重复提交");}
    private String required(String name){String value=env(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少 "+name);return value;}
    private String optional(String name,String fallback){String value=env(name);return value==null||value.isBlank()?fallback:value;}
    private String env(String name){return System.getenv(name);}
    private String safe(String value){if(value==null)return "";String sanitized=value.replaceAll("[\\r\\n\\t]"," ");return sanitized.substring(0,Math.min(sanitized.length(),200));}
}
