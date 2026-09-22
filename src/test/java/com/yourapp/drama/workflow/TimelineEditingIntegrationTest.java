package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TimelineEditingIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired StudioService studio;
    @Autowired TimelineQualityService quality;

    @Test void studioPersistsPauseWithSeparateSourceAndOutputDurations(){
        ObjectNode project=store.create(PROJECT,obj().put("name","剪辑测试").put("idea","停顿"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","室内"));
        ObjectNode shot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","停顿").put("action","人物停住").put("duration",3));
        ObjectNode keyframe=store.create(KEYFRAME,obj().put("projectId",id(project)).put("shotId",id(shot)).put("provider","FIXTURE").put("providerUrl","https://fixture/frame.png"));
        ObjectNode take=store.create(VIDEO_TAKE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("sourceKeyframeId",id(keyframe)).put("sourceProviderUrlSnapshot","https://fixture/frame.png").put("actualDurationMs",2000));
        ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("durationMs",3000).put("contentRevision",1));
        ObjectNode item=obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(shot)).put("videoTakeId",id(take)).put("track","VIDEO").put("startMs",0).put("sourceInMs",0).put("sourceOutMs",2000).put("durationMs",3000).put("pauseDurationMs",1000);item.putArray("editOperations").add("PAUSE");

        ObjectNode saved=studio.create(TIMELINE_ITEM,item);

        assertThat(saved.path("sourceOutMs").asLong()-saved.path("sourceInMs").asLong()).isEqualTo(2000);
        assertThat(saved.path("durationMs").asLong()).isEqualTo(3000);
        assertThat(saved.path("editOperations").toString()).contains("PAUSE");
        assertThat(quality.review(id(timeline)).path("failureCodes").toString()).doesNotContain("VIDEO_TRIM_INVALID","PAUSE_SOURCE_RANGE_INVALID");
    }

    @Test void clipReplacementCannotUseATakeFromAnotherShot(){
        ObjectNode project=store.create(PROJECT,obj().put("name","替换测试").put("idea","两个镜头"));ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","室内"));
        ObjectNode firstShot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","第一镜").put("action","站立").put("duration",3)),secondShot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","第二镜").put("action","转身").put("duration",3));
        ObjectNode firstTake=createTake(project,firstShot,"one"),secondTake=createTake(project,secondShot,"two");ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("durationMs",3000).put("contentRevision",1));
        ObjectNode item=store.create(TIMELINE_ITEM,obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(firstShot)).put("videoTakeId",id(firstTake)).put("track","VIDEO").put("startMs",0).put("sourceInMs",0).put("sourceOutMs",3000).put("durationMs",3000).put("sourceUrl","/takes/one.mp4").put("transition","CUT").put("transitionDurationMs",0));ObjectNode patch=obj().put("revision",revision(item)).put("videoTakeId",id(secondTake)).put("sourceUrl","/takes/two.mp4");patch.putArray("editOperations").add("CLIP_REPLACE");

        assertThatThrownBy(()->studio.update(TIMELINE_ITEM,id(item),patch)).hasMessageContaining("同一镜头");
    }

    @Test void clipReplacementUsesApprovedSameShotTakeAndKeepsAnAuditTrail(){
        ObjectNode project=store.create(PROJECT,obj().put("name","替换审计").put("idea","同镜头选片"));ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","室内"));ObjectNode shot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","动作").put("action","抬头").put("duration",3));ObjectNode firstTake=createTake(project,shot,"first"),replacement=createTake(project,shot,"replacement");ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("durationMs",3000).put("contentRevision",1));ObjectNode item=store.create(TIMELINE_ITEM,obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(shot)).put("videoTakeId",id(firstTake)).put("track","VIDEO").put("startMs",0).put("sourceInMs",0).put("sourceOutMs",3000).put("durationMs",3000).put("sourceUrl","/takes/first.mp4").put("transition","CUT").put("transitionDurationMs",0));ObjectNode patch=obj().put("revision",revision(item)).put("videoTakeId",id(replacement));patch.putArray("editOperations").add("CLIP_REPLACE");

        ObjectNode saved=studio.update(TIMELINE_ITEM,id(item),patch);

        assertThat(text(saved,"sourceUrl")).isEqualTo("/takes/replacement.mp4");assertThat(text(saved,"replacedVideoTakeId")).isEqualTo(id(firstTake));assertThat(saved.path("replacementHistory").get(0).path("toVideoTakeId").asText()).isEqualTo(id(replacement));
    }

    @Test void timelineQualityRejectsAnEditorialLabelThatDoesNotMatchTheCut(){
        ObjectNode project=store.create(PROJECT,obj().put("name","质检测试").put("idea","错误 J-Cut"));ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","室内"));ObjectNode firstDraft=obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","第一镜").put("action","等待").put("duration",3);firstDraft.set("startState",obj());firstDraft.set("endState",obj());ObjectNode secondDraft=obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","第二镜").put("action","说话").put("duration",3);secondDraft.set("startState",obj());secondDraft.set("endState",obj());ObjectNode firstShot=store.create(SHOT,firstDraft),secondShot=store.create(SHOT,secondDraft);ObjectNode firstTake=createTake(project,firstShot,"quality-one"),secondTake=createTake(project,secondShot,"quality-two");ObjectNode timeline=store.create(TIMELINE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("durationMs",6000).put("contentRevision",1).put("previewTimelineRevision",1).put("previewStale",false).put("previewUrl","/preview.mp4"));
        store.create(TIMELINE_ITEM,obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(firstShot)).put("videoTakeId",id(firstTake)).put("track","VIDEO").put("startMs",0).put("sourceInMs",0).put("sourceOutMs",3000).put("durationMs",3000).put("transition","CUT").put("transitionDurationMs",0));store.create(TIMELINE_ITEM,obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(secondShot)).put("videoTakeId",id(secondTake)).put("track","VIDEO").put("startMs",3000).put("sourceInMs",0).put("sourceOutMs",3000).put("durationMs",3000).put("transition","CUT").put("transitionDurationMs",0));ObjectNode dialogue=obj().put("projectId",id(project)).put("timelineId",id(timeline)).put("shotId",id(secondShot)).put("track","DIALOGUE").put("startMs",3000).put("durationMs",1000);dialogue.putArray("editOperations").add("J_CUT");store.create(TIMELINE_ITEM,dialogue);

        ObjectNode review=quality.review(id(timeline));

        assertThat(review.path("failureCodes").toString()).contains("J_CUT_POSITION_INVALID");
    }

    private ObjectNode createTake(ObjectNode project,ObjectNode shot,String suffix){String providerUrl="https://fixture/"+suffix+".png";ObjectNode keyframe=store.create(KEYFRAME,obj().put("projectId",id(project)).put("shotId",id(shot)).put("provider","FIXTURE").put("providerUrl",providerUrl).put("archiveUrl","/frames/"+suffix+".png").put("handoffStatus","HANDED_OFF").put("locked",true).put("selected",true).put("qcStatus","PASSED"));return store.create(VIDEO_TAKE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("sourceKeyframeId",id(keyframe)).put("sourceProviderUrlSnapshot",providerUrl).put("actualDurationMs",3000).put("archiveUrl","/takes/"+suffix+".mp4").put("locked",true).put("selected",true).put("qcStatus","PASSED"));}
}
