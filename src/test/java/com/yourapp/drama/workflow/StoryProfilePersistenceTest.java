package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryProfilePersistenceTest {
    @Autowired StudioService studio;
    @Autowired DocumentStore store;

    @Test void projectPersistsNormalizedStoryProfileAndResolvedFormat() {
        ObjectNode request = obj().put("name", "新故事").put("idea", "陌生人必须在黎明前归还钥匙")
                .put("episodeCount", 24).put("targetDuration", 180).put("distributionProfile", "GENERAL");
        request.set("storyProfile", obj().put("settingGenre", "URBAN").put("storyType", "SUSPENSE")
                .put("audience", "ADULT").put("toneIntensity", "HIGH"));
        request.withObject("storyProfile").putArray("tropes").add("HIDDEN_IDENTITY");
        request.withObject("storyProfile").putArray("tones").add("TENSE");

        ObjectNode saved = studio.create(PROJECT, request);

        assertThat(saved.path("storyProfile").path("storyType").asText()).isEqualTo("SUSPENSE");
        assertThat(saved.path("episodeFormat").path("profileId").asText()).isEqualTo("GENERAL_LONG");
        assertThat(saved.path("episodeFormat").path("family").asText()).isEqualTo("LONG");
        assertThat(saved.path("episodeFormat").path("distributionProfile").asText()).isEqualTo("GENERAL");
        assertThat(saved.path("storyFormat").path("narrativeForm").asText()).isEqualTo("SHORT_DRAMA");
        assertThat(saved.path("storyFormat").path("presentation").asText()).isEqualTo("LIVE_ACTION");
        assertThat(saved.path("storyProfileFingerprint").asText()).hasSize(64);
    }

    @Test void projectGetsUsefulProfileDefaultsInsteadOfLegacyGenre() {
        ObjectNode saved = studio.create(PROJECT, obj().put("name", "默认画像").put("idea", "一次选择改变一家人")
                .put("episodeCount", 10).put("targetDuration", 24));

        assertThat(saved.path("storyProfile").path("storyType").asText()).isEqualTo("GROWTH");
        assertThat(saved.path("storyProfile").path("tropes").isArray()).isTrue();
        assertThat(saved.has("genre")).isFalse();
    }
}
