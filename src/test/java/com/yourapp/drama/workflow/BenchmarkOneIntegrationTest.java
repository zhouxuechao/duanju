package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.storage.MediaStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"drama.render.ffmpeg=frontend/node_modules/ffmpeg-static/ffmpeg.exe","drama.render.ffprobe=frontend/node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe"})
@ActiveProfiles("test")
@Transactional
class BenchmarkOneIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired StudioService studio;
    @Autowired WorkflowService workflow;
    @Autowired PostProductionService post;
    @Autowired GenerationWorker worker;
    @Autowired MediaStorage media;
    @Autowired AssetViewService assetViews;

    @Test void oneCharacterOneLocationSixShotsProduceEighteenSecondPreview() throws Exception {
        assertThat(Path.of("frontend/node_modules/ffmpeg-static/ffmpeg.exe")).as("项目 FFmpeg 运行时").isRegularFile();
        assertThat(Path.of("frontend/node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe")).as("项目 ffprobe 运行时").isRegularFile();
        ObjectNode project=store.create(PROJECT,obj().put("name","Benchmark 1").put("idea","老人发现田埂上的红布").put("ratio","9:16"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","田埂").put("sceneNo",1).set("state",obj()));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",id(project)).put("name","黄昏田埂").put("description","干燥土路与金色稻田，固定黄昏侧光").put("locked",true));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","周伯").put("sourceType","IMAGE_REFERENCE"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",id(project)).put("characterId",id(actor)).put("name","田间装").put("description","靛蓝棉布外套，深灰长裤，黑布鞋").put("locked",true));
        actor=store.update(CHARACTER,id(actor),revision(actor),actor.deepCopy().put("baseLookId",id(look)).put("identityLocked",true));
        NewWorkflowTestFixtures.install(store,assetViews,project,episode,actor,look,location);
        List<ObjectNode> shots=new ArrayList<>();
        for(int number=1;number<=6;number++){
            ObjectNode shot=obj().put("projectId",id(project)).put("sceneId",id(scene)).put("locationId",id(location)).put("purpose","叙事节拍 "+number).put("duration",3).put("shotNo",number)
                .put("shotSize",number%2==0?"CLOSE_UP":"MEDIUM").put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("action",number==1?"周伯停步看向田埂":"周伯完成一个清晰的观察反应").put("visualFocus","周伯的视线与红布").put("relationToPrevious",number==1?"ESTABLISHING":"CONTINUOUS").put("difficulty","B");
            shot.set("cameraPlan",obj().put("position","田埂东侧固定机位").put("height","成人胸口高度").put("distance","4米").put("lensMm",35).put("horizontalAngle","朝向稻田").put("verticalAngle","水平").put("subjectPlacement","画面中央偏右").put("focusPoint","周伯眼睛").put("depthOfField","中等景深").put("lightingDirection","西侧夕阳"));
            shot.putArray("characterIds").add(id(actor));shot.putArray("lookIds").add(id(look));shot.putArray("propIds");shot.putArray("dialogueIds");shot.set("referenceViews",obj().put(id(look),"FRONT").put(id(location),"FRONT"));ArrayNode references=NewWorkflowTestFixtures.approvedViewIds(store,id(project),id(location));references.addAll(NewWorkflowTestFixtures.approvedViewIds(store,id(project),id(look)));shot.set("assetViewIds",references);ObjectNode start=obj().put("locationId",id(location)).put("time","黄昏").put("lighting","西侧夕阳").put("spatialRelations","人物在田埂东侧，稻田在西侧");start.putObject("characters").putObject(id(actor)).put("identityId",id(actor)).put("lookId",id(look)).put("position","田埂东侧").put("lookDirection","朝红布").put("holding","").put("pose","站立").put("actionState","观察");start.putObject("props");shot.set("startState",start);shot.set("endState",start.deepCopy());
            shots.add(studio.create(SHOT,shot));
        }
        for(ObjectNode shot:shots){
            ObjectNode imageJob=workflow.image(id(shot),"KEYFRAME",obj().put("requestKey","benchmark-frame-"+id(shot)));assertThat(text(complete(imageJob),"status")).isEqualTo("SUCCESS");
            ObjectNode frame=store.list(KEYFRAME,id(project),id(shot)).getFirst();workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("score",96));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
            ObjectNode videoJob=workflow.video(id(frame),obj().put("requestKey","benchmark-video-"+id(shot)));assertThat(text(complete(videoJob),"status")).isEqualTo("SUCCESS");
            ObjectNode take=store.list(VIDEO_TAKE,id(project),id(shot)).getFirst();workflow.review(VIDEO_TAKE,id(take),obj().put("passed",true).put("score",94).set("observedState",shot.path("endState")));workflow.lock(VIDEO_TAKE,id(take),obj());
        }
        ObjectNode first=shots.getFirst();ObjectNode voiceProfile=store.create(VOICE_PROFILE,obj().put("projectId",id(project)).put("characterId",id(actor)).put("providerVoiceId","benchmark-elder-voice").put("approved",true).put("name","周伯音色"));ObjectNode line=store.create(DIALOGUE_LINE,obj().put("projectId",id(project)).put("shotId",id(first)).put("characterId",id(actor)).put("voiceProfileId",id(voiceProfile)).put("displayText","红布有来头。").put("dialect","LEIYANG").put("startMs",0).put("endMs",3000));
        line=post.dialect(id(line),obj().put("dialect","LEIYANG").set("correction",obj().put("approved",true).put("dialectText","红布有来头。").put("speechText","红布有来头。")));
        ObjectNode tts=post.tts(id(line),obj().put("requestKey","benchmark-voice"));assertThat(text(complete(tts),"status")).isEqualTo("SUCCESS");ObjectNode clip=store.list(AUDIO_CLIP,id(project),id(first)).getFirst();workflow.lock(AUDIO_CLIP,id(clip),obj());
        ObjectNode timelineRequest=obj().put("requestKey","benchmark-timeline");timelineRequest.set("soundDesign",post.soundDesign(id(episode),obj()));timelineRequest.putArray("soundItems").add(obj().put("track","BGM").put("sourceUrl",required(clip,"archiveUrl")).put("startMs",0).put("durationMs",18_000).put("volume",.12)).add(obj().put("track","SFX").put("sourceUrl",required(clip,"archiveUrl")).put("startMs",9_000).put("durationMs",800).put("volume",.45));
        ObjectNode timelineJob=post.timeline(id(episode),timelineRequest);ObjectNode completedTimeline=complete(timelineJob);assertThat(text(completedTimeline,"status")).as("timeline failure: "+text(completedTimeline,"failureReason")).isEqualTo("SUCCESS");ObjectNode timeline=store.list(TIMELINE,id(project),id(episode)).getFirst();
        assertThat(timeline.path("durationMs").asLong()).isEqualTo(18_000);assertThat(store.list(TIMELINE_ITEM,id(project),id(timeline))).hasSize(9);assertThat(timeline.path("soundDesign").path("status").asText()).isEqualTo("PLAN_READY");
        ObjectNode render=post.render(id(timeline),obj().put("quality","PREVIEW").put("requestKey","benchmark-render"));ObjectNode finished=complete(render);assertThat(text(finished,"status")).isEqualTo("SUCCESS");ObjectNode qa=post.quality(id(timeline));assertThat(qa.path("passed").asBoolean()).isTrue();workflow.lock(TIMELINE,id(timeline),obj());String url=required(finished.path("outputSnapshot"),"videoUrl");try(var stream=media.open(url.substring("/api/media/".length()))){assertThat(stream.readAllBytes().length).isGreaterThan(10_000);}
    }

    private ObjectNode complete(ObjectNode submitted){
        for(int attempt=0;attempt<60;attempt++){
            ObjectNode current=store.get(GENERATION_JOB,id(submitted));
            if(List.of("SUCCESS","FAILED","CANCELLED").contains(text(current,"status")))return current;
            worker.tick();
        }
        return store.get(GENERATION_JOB,id(submitted));
    }
}
