package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.persistence.ResourceKind.EPISODE;
import static com.yourapp.drama.persistence.ResourceKind.SCENE;
import static com.yourapp.drama.persistence.ResourceKind.BEAT;
import static com.yourapp.drama.persistence.ResourceKind.STORY_FACT;
import static com.yourapp.drama.persistence.ResourceKind.STORY_FACT_MUTATION;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryFactMutationOrderingTest {
    @Autowired DocumentStore store;
    @Autowired StoryFactResolver resolver;

    @Test void mutationsAreAppliedByStoryTimeInsteadOfCreationOrder() {
        ObjectNode project=store.create(PROJECT,obj().put("name","倒序补录").put("idea","先记录未来，再补录过去"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","测试场景"));
        ObjectNode beat=store.create(BEAT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","补录事实"));
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","door-state")
                .put("statement","原始状态").put("predicate","STATE").put("validFromStoryTime",0).put("status","ACTIVE"));
        mutation(project,fact,scene,beat,50,"五十秒状态");
        mutation(project,fact,scene,beat,20,"二十秒状态");

        assertThat(resolver.resolveFactAt(id(fact),10).path("statement").asText()).isEqualTo("原始状态");
        assertThat(resolver.resolveFactAt(id(fact),30).path("statement").asText()).isEqualTo("二十秒状态");
        assertThat(resolver.resolveFactBefore(id(fact),50).path("statement").asText()).isEqualTo("二十秒状态");
        assertThat(resolver.resolveFactAt(id(fact),50).path("statement").asText()).isEqualTo("五十秒状态");
        assertThat(resolver.resolveFactAt(id(fact),60).path("statement").asText()).isEqualTo("五十秒状态");
    }

    private void mutation(ObjectNode project,ObjectNode fact,ObjectNode scene,ObjectNode beat,double time,String statement) {
        ObjectNode value=obj().put("projectId",id(project)).put("factId",id(fact)).put("operation","REVISE")
                .put("effectiveFromStoryTime",time).put("reason","测试补录").put("sceneId",id(scene)).put("beatId",id(beat));
        value.set("after",obj().put("statement",statement).put("predicate","STATE").put("status","ACTIVE"));
        store.create(STORY_FACT_MUTATION,value);
    }
}
