package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"drama.render.ffmpeg=frontend/node_modules/ffmpeg-static/ffmpeg.exe","drama.render.ffprobe=frontend/node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EngineeringGovernanceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DocumentStore store;
    @Autowired ObjectMapper mapper;

    @Test
    void ruleExperimentFreezesInputAndNeverMutatesProductionProject() throws Exception {
        ObjectNode project=store.create(PROJECT,obj().put("name","规则实验").put("idea","同一创意比较两套规则"));
        long originalRevision=revision(project);
        ObjectNode body=obj().put("targetPhase","CORE");
        body.set("frozenInputSnapshot",obj().put("idea","雨夜来信").put("storyType","IDENTITY_REVERSAL"));
        body.set("variantA",obj().put("promptVersion","v1").put("rulePackFingerprint","rules-a").put("model","writer-a"));
        body.set("variantB",obj().put("promptVersion","v2").put("rulePackFingerprint","rules-b").put("model","writer-b"));
        JsonNode created=mapper.readTree(mvc.perform(post("/api/projects/{id}/rule-experiments",id(project)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(created.path("frozenInputSnapshotHash").asText()).isNotBlank();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");

        ObjectNode result=obj().put("humanPreference","B").put("note","B 的角色选择更有代价");
        ObjectNode outputs=obj(); outputs.set("A",obj().put("summary","A")); outputs.set("B",obj().put("summary","B")); result.set("outputs",outputs);
        ObjectNode metrics=obj(); metrics.set("A",obj().put("blockingIssues",1)); metrics.set("B",obj().put("blockingIssues",0)); result.set("metrics",metrics);
        JsonNode recorded=mapper.readTree(mvc.perform(put("/api/rule-experiments/{id}",created.path("id").asText()).contentType(MediaType.APPLICATION_JSON).content(result.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(recorded.path("status").asText()).isEqualTo("REVIEWED");
        assertThat(recorded.path("humanPreference").asText()).isEqualTo("B");
        assertThat(revision(store.get(PROJECT,id(project)))).isEqualTo(originalRevision);
    }

    @Test
    void pipelineRunExecutesMockProjectToFinalMp4AndKeepsSuccessfulCheckpointsOnResume() throws Exception {
        ObjectNode fixture=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("test-fixtures/e2e/golden-basic/project.json")));
        fixture.put("idea","一只失踪的铜铃迫使两位村民在夜色中重新面对一桩旧事").put("ratio","9:16");
        ObjectNode project=store.create(PROJECT,fixture);
        JsonNode started=mapper.readTree(mvc.perform(post("/api/projects/{id}/pipeline-runs",id(project)).contentType(MediaType.APPLICATION_JSON)
            .content(obj().put("mode","MOCK").put("scenarioId","golden-basic").toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(started.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(started.path("resumeFromStage").asText()).isBlank();
        assertThat(started.path("stages")).anyMatch(stage->stage.path("stage").asText().equals("PREFLIGHT")&&stage.path("status").asText().equals("SUCCESS"));
        assertThat(started.path("stages")).extracting(stage->stage.path("stage").asText()).containsExactly(
            "PREFLIGHT","STORY","DIRECTOR","ASSET","KEYFRAME","VIDEO","AUDIO","TIMELINE","PREVIEW","CREATIVE_QA","FINAL");
        assertThat(started.path("stages")).allMatch(stage->stage.path("status").asText().equals("SUCCESS"));
        String runId=started.path("id").asText();
        ObjectNode timeline=store.list(TIMELINE,id(project),null).getFirst();
        assertThat(text(timeline,"previewUrl")).startsWith("/api/media/renders/");
        assertThat(text(timeline,"finalUrl")).startsWith("/api/media/renders/");
        assertThat(text(timeline,"finalQaStatus")).isEqualTo("PASSED");
        assertThat(timeline.path("finalCreativeQa").fieldNames()).toIterable().contains(
            "characterConsistency","propContinuity","positionContinuity","actionContinuity","dialogueQuality","bgmFit","sfxAccuracy","hook","midHook","cliffhanger");
        assertThat(store.list(STORYBOARD,id(project),null)).isNotEmpty().allMatch(board->"PREVIS".equals(text(board,"mediaPurpose")));
        assertThat(store.list(KEYFRAME,id(project),null)).allMatch(frame->"KEYFRAME".equals(text(frame,"mediaPurpose")))
            .anyMatch(frame->frame.path("locked").asBoolean()&&frame.path("selected").asBoolean()&&"FINAL_REFERENCE".equals(text(frame,"selectedPurpose")));
        assertThat(store.list(QC_RESULT,id(project),null)).anyMatch(review->!review.path("passed").asBoolean());
        assertThat(store.list(DIALOGUE_LINE,id(project),null)).isNotEmpty();
        assertThat(store.list(AUDIO_CLIP,id(project),null)).allMatch(clip->clip.path("locked").asBoolean());
        assertThat(store.list(TIMELINE_ITEM,id(project),null)).anyMatch(item->"BGM".equals(text(item,"track"))).anyMatch(item->"SFX".equals(text(item,"track")));
        assertThat(store.list(GENERATION_JOB,id(project),null)).anyMatch(job->"VIDEO".equals(text(job,"type"))&&"FULL_MODAL_REFERENCE".equals(text(job.path("inputSnapshot"),"videoRequestRoute")));
        int completedJobs=(int)store.list(GENERATION_JOB,id(project),null).stream().filter(job->"SUCCESS".equals(text(job,"status"))).count();

        JsonNode resumed=mapper.readTree(mvc.perform(post("/api/pipeline-runs/{id}/resume",runId).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(resumed.path("id").asText()).isEqualTo(runId);
        assertThat(resumed.path("attempt").asInt()).isEqualTo(1);
        assertThat(store.list(PIPELINE_RUN,id(project),null)).hasSize(1);
        assertThat((int)store.list(GENERATION_JOB,id(project),null).stream().filter(job->"SUCCESS".equals(text(job,"status"))).count()).isEqualTo(completedJobs);
        assertThat(store.list(STAGE_RUN,id(project),runId)).isNotEmpty();

        ObjectNode currentTimeline=store.get(TIMELINE,id(timeline));
        ObjectNode interruptedTimeline=currentTimeline.deepCopy().put("finalQaStatus","TECHNICAL_PASSED");interruptedTimeline.remove("finalCreativeQa");
        store.update(TIMELINE,id(currentTimeline),revision(currentTimeline),interruptedTimeline);
        ObjectNode interrupted=store.create(PIPELINE_RUN,obj().put("projectId",id(project)).put("scenarioId","golden-basic").put("mode","MOCK").put("status","WAITING").put("attempt",1).put("startedAt",java.time.Instant.now().toString()));
        JsonNode recovered=mapper.readTree(mvc.perform(post("/api/pipeline-runs/{id}/resume",id(interrupted)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(recovered.path("attempt").asInt()).isEqualTo(2);
        assertThat(text(store.get(TIMELINE,id(timeline)),"finalQaStatus")).isEqualTo("PASSED");
        assertThat((int)store.list(GENERATION_JOB,id(project),null).stream().filter(job->"SUCCESS".equals(text(job,"status"))).count()).isEqualTo(completedJobs);
    }

    @Test
    void pipelineCanSkipPaidPrevisWithoutBlockingKeyframesOrFinalRender() throws Exception {
        ObjectNode fixture=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("test-fixtures/e2e/golden-basic/project.json")));
        fixture.put("idea","一封被雨水浸透的信让两位邻居在车站重逢").put("ratio","9:16").put("previsMode","SKIP");
        ObjectNode project=store.create(PROJECT,fixture);
        JsonNode started=mapper.readTree(mvc.perform(post("/api/projects/{id}/pipeline-runs",id(project)).contentType(MediaType.APPLICATION_JSON)
            .content(obj().put("mode","MOCK").put("scenarioId","skip-previs").toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(started.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(store.list(STORYBOARD,id(project),null)).isEmpty();
        assertThat(store.list(KEYFRAME,id(project),null)).isNotEmpty().allMatch(frame->"KEYFRAME".equals(text(frame,"mediaPurpose")));
        assertThat(store.list(TIMELINE,id(project),null)).anyMatch(timeline->!text(timeline,"finalUrl").isBlank());
    }
}
