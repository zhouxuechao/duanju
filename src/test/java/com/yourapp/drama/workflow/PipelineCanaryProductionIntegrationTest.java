package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={"drama.render.ffmpeg=frontend/node_modules/ffmpeg-static/ffmpeg.exe","drama.render.ffprobe=frontend/node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe"})
@ActiveProfiles("test")
@Transactional
class PipelineCanaryProductionIntegrationTest {
    @Autowired PipelineCanaryProductionAdapter adapter;
    @Autowired DocumentStore store;
    @Autowired StudioService studio;
    @Autowired ObjectMapper mapper;

    @Test
    void fixedIdeaRunsThroughThePersistedProductionWorkflow() {
        assertThat(new PipelineCanaryProductionGate().requireReady(adapter)).hasSize(PipelineCanaryProductionAdapter.Capability.values().length);
        ObjectNode result=adapter.start(PipelineCanaryFixture.standard(),"production-e2e","MOCK");
        String projectId=text(result,"projectId");

        assertThat(text(result,"status")).as(result.toPrettyString()).isEqualTo("SUCCESS");
        assertThat(result.path("productionCounts").path("episodes").asInt()).isEqualTo(1);
        assertThat(result.path("productionCounts").path("scenes").asInt()).isEqualTo(1);
        assertThat(result.path("productionCounts").path("beats").asInt()).isEqualTo(2);
        assertThat(result.path("productionCounts").path("shots").asInt()).isEqualTo(4);
        assertThat(result.path("productionCounts").path("characters").asInt()).isEqualTo(2);
        assertThat(result.path("productionCounts").path("locations").asInt()).isEqualTo(1);
        assertThat(result.path("productionCounts").path("props").asInt()).isEqualTo(1);
        assertThat(result.path("productionCounts").path("dialogues").asInt()).isEqualTo(2);
        assertThat(store.list(STORY_FACT,projectId,null)).hasSize(1);
        assertThat(store.list(CHARACTER_KNOWLEDGE,projectId,null)).hasSize(3);
        assertThat(store.list(PROP_STATE,projectId,null)).hasSize(2);

        List<ObjectNode> shots=store.list(SHOT,projectId,null).stream().filter(value->!value.path("stale").asBoolean())
            .sorted(java.util.Comparator.comparingInt(value->value.path("shotNo").asInt())).toList();
        assertThat(shots).hasSize(4).allSatisfy(shot->{
            assertThat(shot.path("beatResourceId").asText()).isNotBlank();
            assertThat(shot.path("storyTime").isNumber()).isTrue();
            assertThat(shot.path("propIds")).hasSize(1);
        });
        assertThat(shots.subList(1,4)).allSatisfy(shot->assertThat(text(shot,"relationToPrevious")).isEqualTo("CONTINUOUS"));
        assertThat(store.list(PROMPT_VERSION,projectId,null)).isNotEmpty().allSatisfy(prompt->assertThat(prompt.path("promptIR")).isNotEmpty());
        ObjectNode firstCharacter=store.list(CHARACTER,projectId,null).stream().filter(value->"林川".equals(text(value,"name"))).findFirst().orElseThrow();
        ObjectNode secondCharacter=store.list(CHARACTER,projectId,null).stream().filter(value->"苏宁".equals(text(value,"name"))).findFirst().orElseThrow();
        ObjectNode phone=store.list(PROP,projectId,null).getFirst();String factId=id(store.list(STORY_FACT,projectId,null).getFirst());
        List<ObjectNode> keyframePrompts=store.list(PROMPT_VERSION,projectId,null).stream().filter(value->"KEYFRAME".equals(text(value,"purpose"))).toList();
        ObjectNode shot2Prompt=keyframePrompts.stream().filter(value->id(shots.get(1)).equals(text(value,"shotId"))).findFirst().orElseThrow();
        ObjectNode shot4Prompt=keyframePrompts.stream().filter(value->id(shots.get(3)).equals(text(value,"shotId"))).findFirst().orElseThrow();
        assertThat(shot2Prompt.path("inputSnapshot").path("characterKnowledge").path(id(secondCharacter)).path("unknownFacts").toString()).contains(factId);
        assertThat(shot4Prompt.path("inputSnapshot").path("characterKnowledge").path(id(secondCharacter)).path("knownFacts").toString()).contains(factId);
        assertThat(text(shot2Prompt.path("inputSnapshot").path("propStates").path(id(phone)),"carriedBy")).isEqualTo(id(firstCharacter));
        assertThat(text(shot4Prompt.path("inputSnapshot").path("propStates").path(id(phone)),"carriedBy")).isEqualTo(id(secondCharacter));
        assertThat(result.path("keyframeArtifactIds")).hasSize(4);
        assertThat(result.path("selectedTakeIds")).hasSize(4);
        assertThat(result.path("audioArtifactIds")).hasSize(2);

        List<ObjectNode> items=store.list(TIMELINE_ITEM,projectId,text(result,"timelineId"));
        assertThat(items.stream().filter(item->"VIDEO".equals(text(item,"track")))).hasSize(4);
        assertThat(items.stream().filter(item->"DIALOGUE".equals(text(item,"track")))).hasSize(2);
        ObjectNode timeline=store.get(TIMELINE,text(result,"timelineId"));
        assertThat(timeline.path("durationMs").asLong()).isEqualTo(20_000);
        assertThat(text(timeline,"finalUrl")).isNotBlank();
        assertThat(text(timeline,"finalQaStatus")).isEqualTo("PASSED");

        List<ObjectNode> jobs=store.list(GENERATION_JOB,projectId,null);
        assertThat(jobs.stream().filter(job->"KEYFRAME".equals(text(job,"type")))).hasSize(4);
        assertThat(jobs.stream().filter(job->"VIDEO".equals(text(job,"type")))).hasSize(4);
        assertThat(jobs.stream().filter(job->"TTS".equals(text(job,"type")))).hasSize(2);
        assertThat(jobs).allSatisfy(job->{assertThat(job.path("inputSnapshot").path("testRun").asBoolean()).isTrue();assertThat(text(job.path("inputSnapshot"),"testRunId")).isEqualTo("production-e2e");assertThat(text(job.path("inputSnapshot"),"testPhase")).isEqualTo("PIPELINE");});
        assertThat(store.list(DIALOGUE_LINE,projectId,null)).allSatisfy(line->{assertThat(text(line,"semanticText")).isNotBlank();assertThat(text(line,"spokenText")).isNotBlank();assertThat(text(line,"subtitleText")).isNotBlank();assertThat(text(line,"voiceProfileId")).isNotBlank();});
        assertThat(store.list(AUDIO_CLIP,projectId,null)).allSatisfy(clip->{assertThat(clip.path("locked").asBoolean()).isTrue();assertThat(clip.path("selected").asBoolean()).isTrue();assertThat(clip.path("voiceSnapshot").isObject()).isTrue();});
        List<ObjectNode> reviews=store.list(QC_RESULT,projectId,null);
        assertThat(reviews.stream().filter(review->KEYFRAME.path().equals(text(review,"targetKind"))&&"AUTOMATIC".equals(text(review,"reviewer"))&&!review.path("shadow").asBoolean()&&review.path("passed").asBoolean())).hasSize(4);
        assertThat(reviews.stream().filter(review->VIDEO_TAKE.path().equals(text(review,"targetKind"))&&"AUTOMATIC".equals(text(review,"reviewer"))&&!review.path("shadow").asBoolean()&&review.path("passed").asBoolean())).hasSize(4);
        assertThat(store.list(VIDEO_TAKE,projectId,null)).allSatisfy(take->{assertThat(text(take,"sourceKeyframeId")).isNotBlank();assertThat(text(take,"promptVersionId")).isNotBlank();assertThat(text(take,"providerRequestId")).isNotBlank();assertThat(text(take,"generationProfile")).isEqualTo("TEST");ObjectNode frame=store.get(KEYFRAME,text(take,"sourceKeyframeId"));assertThat(text(take,"sourceProviderUrlSnapshot")).isEqualTo(text(frame,"providerUrl"));});

        int generationJobs=jobs.size();
        ObjectNode resumed=adapter.resume(text(result,"pipelineRunId"));
        assertThat(text(resumed,"status")).isEqualTo("SUCCESS");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(generationJobs);

        ObjectNode staleCursor=result.deepCopy();
        staleCursor.withArray("providerJobs").forEach(item->((ObjectNode)item).put("status","SUBMITTED"));
        ObjectNode reconciled=adapter.reconcile(staleCursor);
        assertThat(text(reconciled,"status")).isEqualTo("SUCCESS");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(generationJobs);

        ObjectNode restarted=adapter.start(PipelineCanaryFixture.standard(),"production-e2e","MOCK");
        assertThat(text(restarted,"projectId")).isEqualTo(projectId);
        assertThat(store.list(PROJECT,null,null).stream().filter(value->"production-e2e".equals(text(value,"pipelineCanaryRunId")))).hasSize(1);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(generationJobs);

        ObjectNode conflicting=result.deepCopy();
        ObjectNode providerJob=(ObjectNode)conflicting.withArray("providerJobs").get(0);
        providerJob.put("providerTaskId","conflicting-provider-task");
        assertThatThrownBy(()->adapter.reconcile(conflicting))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("RECONCILIATION_REQUIRED"));
    }

    @Test void restartAfterProjectCommitContinuesTheSameProjectInsteadOfCreatingAnother(){
        String runId="project-committed-before-pipeline";
        ObjectNode orphan=studio.create(PROJECT,PipelineCanaryFixture.standard().productionProject(mapper).put("pipelineCanaryRunId",runId).put("testRun",true).put("testRunId",runId).put("testPhase","PIPELINE"));

        ObjectNode result=adapter.start(PipelineCanaryFixture.standard(),runId,"MOCK");

        assertThat(text(result,"status")).isEqualTo("SUCCESS");
        assertThat(text(result,"projectId")).isEqualTo(id(orphan));
        assertThat(store.list(PROJECT,null,null).stream().filter(value->runId.equals(text(value,"pipelineCanaryRunId")))).hasSize(1);
    }
}
