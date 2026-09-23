package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.production.PaidProviderPreflight;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VoiceStatePaidPreflightTest {
    @Autowired DocumentStore store;
    @Autowired PostProductionService post;
    @Autowired GenerationWorker worker;
    @Autowired PaidProviderPreflight preflight;
    @Autowired AssetViewService assetViews;

    @Test void queuedTtsUsesFrozenReferenceAudioFromVoiceStateEvenWhenProfileChanges() {
        Fixture f=fixture(10);
        store.create(VOICE_STATE,obj().put("projectId",id(f.project)).put("voiceProfileId",id(f.profile)).put("state","CHILD")
                .put("referenceAudioUrl","https://media.example.com/child.wav").put("validFromStoryTime",0).put("validToStoryTime",18));
        ObjectNode job=post.tts(id(f.line),obj().put("requestKey","frozen-voice-reference"));
        ObjectNode current=store.get(VOICE_PROFILE,id(f.profile));
        store.update(VOICE_PROFILE,id(current),revision(current),current.deepCopy().put("providerVoiceId","adult-now"));

        ObjectNode frozen=store.get(GENERATION_JOB,id(job));
        preflight.requirePass(frozen);
        assertThat(text(frozen.path("inputSnapshot").path("voiceProfile"),"referenceAudioUrl"))
                .isEqualTo("https://media.example.com/child.wav");
    }

    @Test void changingSpokenTextAfterEnqueueRejectsTheFrozenJobBeforeProviderDispatch() {
        Fixture f=fixture(10);
        store.create(VOICE_STATE,obj().put("projectId",id(f.project)).put("voiceProfileId",id(f.profile)).put("state","CHILD")
                .put("providerVoiceId","child-voice").put("validFromStoryTime",0).put("validToStoryTime",18));
        ObjectNode job=post.tts(id(f.line),obj().put("requestKey","stale-dialogue"));
        ObjectNode current=store.get(DIALOGUE_LINE,id(f.line));
        store.update(DIALOGUE_LINE,id(current),revision(current),current.deepCopy().put("spokenText","修改后的台词"));

        worker.tick();

        ObjectNode failed=store.get(GENERATION_JOB,id(job));
        assertThat(text(failed,"status")).isEqualTo("FAILED");
        assertThat(text(failed,"failureCode")).isEqualTo("STALE_DIALOGUE_JOB");
    }

    private Fixture fixture(double storyTime) {
        ObjectNode project=store.create(PROJECT,obj().put("name","声音快照").put("idea","不同年龄使用不同声音"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        NewWorkflowTestFixtures.install(store,assetViews,project,episode,null,null,null);
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","室内"));
        ObjectNode actor=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","角色"));
        ObjectNode shot=store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("duration",3).put("status","PLANNED")
                .put("storyTime",storyTime).put("action","说话").put("purpose","对白").put("relationToPrevious","CUT"));
        ObjectNode profile=store.create(VOICE_PROFILE,obj().put("projectId",id(project)).put("name","角色音色")
                .put("characterId",id(actor)).put("approved",true));
        ObjectNode line=store.create(DIALOGUE_LINE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("characterId",id(actor))
                .put("voiceProfileId",id(profile)).put("semanticText","我记得那一年。").put("spokenText","我记得那一年。").put("subtitleText","我记得那一年。"));
        return new Fixture(project,profile,line);
    }

    private record Fixture(ObjectNode project,ObjectNode profile,ObjectNode line) {}
}
