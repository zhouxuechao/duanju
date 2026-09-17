package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.voice.VoiceGenerator;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AudioArchiveRecoveryIntegrationTest {
    @Autowired DocumentStore store; @Autowired PostProductionService post; @Autowired GenerationWorker worker; @Autowired JobService jobs; @Autowired AssetViewService assetViews;
    @MockitoBean VoiceGenerator voice; @MockitoBean MediaStorage storage; @MockitoBean ProviderMediaFetcher fetcher;

    @Test void providerSuccessWithArchiveFailureMustLeaveIndependentArchiveJob() {
        ObjectNode project=store.create(PROJECT,obj().put("name","音频归档恢复").put("idea","对白"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","屋内"));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",id(project)).put("name","屋内").put("description","旧屋"));
        NewWorkflowTestFixtures.install(store,assetViews,project,episode,null,null,location);
        ObjectNode shot=obj().put("projectId",id(project)).put("sceneId",id(scene)).put("locationId",id(location)).put("purpose","对白").put("duration",3).put("status","VIDEO_LOCKED");
        shot.putArray("characterIds"); shot.putArray("propIds"); shot.putArray("dialogueIds");
        shot.set("assetViewIds",NewWorkflowTestFixtures.approvedViewIds(store,id(project),id(location)));
        shot.set("startState",obj()); shot.set("endState",obj().put("reviewed",true));
        shot=store.create(SHOT,shot);
        ObjectNode profile=store.create(VOICE_PROFILE,obj().put("projectId",id(project)).put("providerVoiceId","voice-1").put("approved",true));
        ObjectNode line=store.create(DIALOGUE_LINE,obj().put("projectId",id(project)).put("shotId",id(shot)).put("displayText","别开门").put("speechText","别开门").put("voiceProfileId",id(profile)));
        when(voice.generate(any())).thenReturn(new VoiceGenerator.VoiceResult("VOLCENGINE","seed-audio","cgt-audio-1","https://audio.volces.com/result.mp3",new byte[]{1,2,3},"audio/mpeg",2,false));
        when(storage.put(anyString(),any(),anyString())).thenThrow(new RuntimeException("archive down"));
        ObjectNode tts=post.tts(id(line),obj().put("requestKey","audio-archive-recovery"));
        worker.tick(); worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(tts)),"status")).isEqualTo("SUCCESS");
        ObjectNode clip=store.list(AUDIO_CLIP,id(project),id(shot)).getFirst();
        assertThat(text(clip,"providerUrl")).isEqualTo("https://audio.volces.com/result.mp3");
        ObjectNode archive=store.list(GENERATION_JOB,id(project),null).stream().filter(j->"ARCHIVE".equals(text(j,"type"))).findFirst().orElseThrow();
        assertThat(text(archive,"status")).isEqualTo("FAILED");
        when(fetcher.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        doReturn("/api/media/audio.mp3").when(storage).put(anyString(),any(),anyString());
        ObjectNode retried=jobs.retry(id(archive));
        worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(retried)),"status")).isEqualTo("SUCCESS");
        assertThat(text(store.get(AUDIO_CLIP,id(clip)),"archiveUrl")).isEqualTo("/api/media/audio.mp3");
        verify(voice,times(1)).generate(any());
    }
}
