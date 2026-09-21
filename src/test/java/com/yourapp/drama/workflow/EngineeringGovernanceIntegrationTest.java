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

@SpringBootTest
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
    void pipelineRunPersistsStageCheckpointsAndResumesSameRun() throws Exception {
        ObjectNode project=store.create(PROJECT,obj().put("name","断点验收").put("idea","测试").put("episodeCount",1).put("targetDuration",24).put("ratio","9:16"));
        JsonNode started=mapper.readTree(mvc.perform(post("/api/projects/{id}/pipeline-runs",id(project)).contentType(MediaType.APPLICATION_JSON)
            .content(obj().put("mode","MOCK").put("scenarioId","golden-basic").toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(started.path("status").asText()).isEqualTo("WAITING");
        assertThat(started.path("resumeFromStage").asText()).isEqualTo("CORE");
        assertThat(started.path("stages")).anyMatch(stage->stage.path("stage").asText().equals("PREFLIGHT")&&stage.path("status").asText().equals("SUCCESS"));
        String runId=started.path("id").asText();

        JsonNode resumed=mapper.readTree(mvc.perform(post("/api/pipeline-runs/{id}/resume",runId).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(resumed.path("id").asText()).isEqualTo(runId);
        assertThat(resumed.path("attempt").asInt()).isEqualTo(2);
        assertThat(store.list(PIPELINE_RUN,id(project),null)).hasSize(1);
        assertThat(store.list(STAGE_RUN,id(project),runId)).isNotEmpty();
    }
}
