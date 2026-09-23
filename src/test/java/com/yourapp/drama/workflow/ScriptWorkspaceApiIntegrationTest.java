package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScriptWorkspaceApiIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void humanEditForksPreservedVersionDiffsAndOpensConfirmedGate() throws Exception {
        ObjectNode project=store.create(PROJECT,obj().put("name","剧本工作台").put("idea","一封迟到的信").put("sourceMode","IDEA").put("scriptWorkspaceRequired",true));
        ObjectNode content=obj().put("title","迟到的信").put("summary","母女解开心结").put("openingHook","门外传来敲门声").put("endingHook","信封里还有一张照片");
        content.putArray("scenes").add(obj().put("sceneKey","S1").put("title","客厅").put("location","客厅").put("time","夜").put("description","母亲拆信").set("dialogues",mapper.createArrayNode().add(obj().put("lineKey","D1").put("character","母亲").put("text","你终于写信了").put("ownership","AI_GENERATED"))));
        ObjectNode legacy=store.create(STORY_DOCUMENT,obj().put("projectId",id(project)).put("documentType","EPISODE_SCRIPT").put("episodeNo",1).put("reviewStatus","CONFIRMED").set("content",content));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("episodeNo",1).put("storyDocumentId",id(legacy)).put("name","迟到的信"));

        ObjectNode v1=read(mvc.perform(get("/api/episodes/{id}/script",id(episode))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode v2=read(mvc.perform(post("/api/episodes/{id}/script/fork",id(episode)).contentType("application/json").content(obj().put("revision",revision(v1)).put("createdBy","USER").toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode edited=(ObjectNode)v2.deepCopy();edited.with("structuredContent").put("summary","母女在旧信中发现失踪父亲留下的线索");
        ObjectNode dialogue=(ObjectNode)edited.path("structuredContent").path("scenes").get(0).path("dialogues").get(0);dialogue.put("text","这封信，我等了二十年").put("ownership","HUMAN_LOCKED");
        v2=read(mvc.perform(put("/api/script-versions/{id}",id(v2)).contentType("application/json").content(edited.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode diff=read(mvc.perform(get("/api/script-versions/{from}/diff/{to}",id(v1),id(v2))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode confirmed=read(mvc.perform(post("/api/script-versions/{id}/confirm",id(v2)).contentType("application/json").content(obj().put("revision",revision(v2)).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(text(store.get(EPISODE_SCRIPT_VERSION,id(v1)),"status")).isEqualTo("SUPERSEDED");
        assertThat(text(confirmed,"status")).isEqualTo("CONFIRMED");
        assertThat(diff.path("changedFields").toString()).contains("summary");
        assertThat(diff.path("changedDialogues")).isNotEmpty();
        ObjectNode snapshot=store.get(PRODUCTION_SCRIPT_SNAPSHOT,text(confirmed,"productionScriptSnapshotId"));
        assertThat(text(snapshot,"scriptVersionId")).isEqualTo(id(confirmed));
        assertThat(text(snapshot,"scriptHash")).hasSize(64);
        assertThat(store.get(EPISODE,id(episode)).path("productionReady").asBoolean()).isTrue();
    }

    private ObjectNode read(String json)throws Exception{return (ObjectNode)mapper.readTree(json);}
}
