package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.GENERATION_JOB;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.persistence.ResourceKind.STORY_DOCUMENT;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryQualityEvidenceContextTest {
    @Autowired DocumentStore store;
    @Autowired StoryQualityService quality;

    @Test
    void storyQaReceivesAllCanonicalCharacterLocationAndPropIds() {
        ObjectNode project = store.create(PROJECT, obj().put("name", "证据世界").put("idea", "失踪的钥匙").put("episodeCount", 1));
        ObjectNode content = obj();
        content.putArray("characters").add(obj().put("characterKey", "CH001"));
        content.putArray("locations").add(obj().put("locationKey", "LOC001"));
        content.putArray("props").add(obj().put("propKey", "PROP001"));
        ObjectNode core = store.create(STORY_DOCUMENT, obj().put("projectId", id(project)).put("documentType", "CORE")
            .put("reviewStatus", "CONFIRMED").set("content", content));
        ObjectNode snapshot = obj();
        snapshot.set("storyProfile", obj().put("storyType", "SUSPENSE"));
        ObjectNode scriptDraft = obj().put("projectId", id(project)).put("coreId", id(core))
            .put("documentType", "EPISODE_SCRIPT").put("episodeNo", 1);
        scriptDraft.set("projectSnapshot", snapshot);
        scriptDraft.set("sourceSnapshot", obj().set("episodeOutline", obj().put("episodeFunction", "调查")));
        scriptDraft.set("content", obj().put("title", "第一集"));
        ObjectNode script = store.create(STORY_DOCUMENT, scriptDraft);

        quality.schedule(script);

        ObjectNode job = store.list(GENERATION_JOB, id(project), null).stream()
            .filter(value -> "STORY_QA".equals(value.path("type").asText())).findFirst().orElseThrow();
        assertThat(job.path("inputSnapshot").path("evidenceWorld").path("characterIds").toString()).contains("CH001");
        assertThat(job.path("inputSnapshot").path("evidenceWorld").path("locationIds").toString()).contains("LOC001");
        assertThat(job.path("inputSnapshot").path("evidenceWorld").path("propIds").toString()).contains("PROP001");
    }
}
