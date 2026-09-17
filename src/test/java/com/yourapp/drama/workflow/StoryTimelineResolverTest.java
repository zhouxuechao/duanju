package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
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
class StoryTimelineResolverTest {
    @Autowired DocumentStore store;
    @Autowired StoryTimelineResolver timelines;

    @Test void presentationOrderMayJumpAcrossStoryChronology() {
        ObjectNode project = store.create(PROJECT, obj().put("name","倒叙时间线").put("idea","悬疑"));
        ObjectNode episode = store.create(EPISODE, obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene = store.create(SCENE, obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","倒叙"));
        ObjectNode flashback = store.create(SHOT, obj().put("projectId",id(project)).put("sceneId",id(scene)).put("shotNo",1).put("presentationOrder",2).put("storyTime",2010).put("timelineType","FLASHBACK"));
        ObjectNode present = store.create(SHOT, obj().put("projectId",id(project)).put("sceneId",id(scene)).put("shotNo",2).put("presentationOrder",1).put("storyTime",2026).put("timelineType","NORMAL"));
        JsonNode items = timelines.resolve(id(project),id(episode)).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("shotId").asText()).isEqualTo(id(present));
        assertThat(items.get(0).path("storyTime").asDouble()).isEqualTo(2026);
        assertThat(items.get(1).path("shotId").asText()).isEqualTo(id(flashback));
        assertThat(items.get(1).path("storyTime").asDouble()).isEqualTo(2010);
        assertThat(items.get(1).path("timelineType").asText()).isEqualTo("FLASHBACK");
    }
}
