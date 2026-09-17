package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.*;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.storage.MediaStorage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="drama.render.ffmpeg=D:/project/aimanju/node_modules/ffmpeg-static/ffmpeg.exe")
@ActiveProfiles("test")
@Transactional
class PostProductionIntegrationTest {
    @Autowired DocumentStore store;@Autowired PostProductionService post;@Autowired WorkflowService workflow;@Autowired GenerationWorker worker;@Autowired MediaStorage media;@Autowired AssetViewService assetViews;
    @Test void dialectTtsTimelineAndPreviewRenderComplete()throws Exception{
        Assumptions.assumeTrue(Files.isRegularFile(Path.of("D:/project/aimanju/node_modules/ffmpeg-static/ffmpeg.exe")));
        ObjectNode project=store.create(PROJECT,obj().put("name","后期测试").put("idea","一句方言对白"));ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","屋内").put("sceneNo",1));ObjectNode location=store.create(LOCATION,obj().put("projectId",id(project)).put("name","屋内").put("locationKey","room").put("description","方桌、北窗和旧木门"));NewWorkflowTestFixtures.install(store,assetViews,project,episode,null,null,location);
        ObjectNode shot=obj().put("projectId",id(project)).put("sceneId",id(scene)).put("locationId",id(location)).put("purpose","说话").put("action","人物平静说出一句话").put("duration",3).put("shotNo",1).put("status","VIDEO_LOCKED");shot.putArray("characterIds");shot.putArray("propIds");shot.putArray("dialogueIds");shot.set("assetViewIds",NewWorkflowTestFixtures.approvedViewIds(store,id(project),id(location)));shot.set("startState",obj());shot.set("endState",obj().put("reviewed",true));shot=store.create(SHOT,shot);
        ObjectNode voiceProfile=store.create(VOICE_PROFILE,obj().put("projectId",id(project)).put("providerVoiceId","fixture-voice").put("approved",true).put("name","测试角色音色"));ObjectNode line=store.create(DIALOGUE_LINE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("displayText","你今天到哪里去了？").put("dialect","LEIYANG").put("startMs",300).put("endMs",2600).put("voiceProfileId",id(voiceProfile)));ObjectNode correction=obj().put("dialect","LEIYANG");correction.set("correction",obj().put("approved",true).put("dialectText","本地方言表达").put("speechText","本地方言发音"));line=post.dialect(id(line),correction);assertThat(text(line,"displayText")).isEqualTo("你今天到哪里去了？");assertThat(text(line,"speechText")).isEqualTo("本地方言发音");
        ObjectNode tts=post.tts(id(line),obj());worker.tick();assertThat(text(store.get(GENERATION_JOB,id(tts)),"status")).isEqualTo("SUCCESS");ObjectNode clip=store.list(AUDIO_CLIP,id(project),id(shot)).getFirst();workflow.lock(AUDIO_CLIP,id(clip),obj());
        ObjectNode prompt=store.create(PROMPT_VERSION,obj().put("projectId",id(project)).put("shotId",id(shot)).put("purpose","VIDEO").put("version",1).put("prompt","test"));String providerUrl="https://image.volces.com/frame.png?sig=original";ObjectNode frame=store.create(KEYFRAME,obj().put("projectId",id(project)).put("shotId",id(shot)).put("provider","VOLCENGINE").put("sourceModel","seedream").put("providerUrl",providerUrl).put("archiveUrl","/demo/keyframe.svg").put("handoffStatus","HANDED_OFF").put("qcStatus","PASSED").put("locked",true).put("selected",true).set("assetViewIds",NewWorkflowTestFixtures.approvedViewIds(store,id(project),id(location))));
        store.create(VIDEO_TAKE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("takeNo",1).put("provider","VOLCENGINE").put("model","seedance").put("promptVersionId",id(prompt)).put("providerRequestId","task-1").put("sourceKeyframeId",id(frame)).put("sourceProviderUrlSnapshot",providerUrl).put("videoUrl","https://video.volces.com/take.mp4").put("archiveUrl","/demo/take.mp4").put("providerStatus","SUCCEEDED").put("qcStatus","PASSED").put("qcScore",100).put("selected",true).put("locked",true).put("cost",0));
        ObjectNode timelineJob=post.timeline(id(episode),obj());worker.tick();assertThat(text(store.get(GENERATION_JOB,id(timelineJob)),"status")).isEqualTo("SUCCESS");ObjectNode timeline=store.list(TIMELINE,id(project),id(episode)).getFirst();workflow.lock(TIMELINE,id(timeline),obj());ObjectNode render=post.render(id(timeline),obj().put("quality","PREVIEW"));worker.tick();ObjectNode finished=store.get(GENERATION_JOB,id(render));assertThat(text(finished,"status")).isEqualTo("SUCCESS");String url=required(finished.path("outputSnapshot"),"videoUrl");assertThat(url).startsWith("/api/media/renders/");try(var result=media.open(url.substring("/api/media/".length()))){assertThat(result.readAllBytes().length).isGreaterThan(1024);}
    }
}
