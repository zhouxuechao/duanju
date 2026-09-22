package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryFactIntegrationTest {
    @Autowired DocumentStore store; @Autowired StoryFactResolver facts; @Autowired StudioService studio;

    @Test void characterKnowledgeMustRespectStoryTimeAndCannotLeakWorldTruth(){
        ObjectNode project=store.create(PROJECT,obj().put("name","事实时间").put("idea","悬疑"));
        ObjectNode detective=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","张三"));
        ObjectNode suspect=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","李四"));
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","killer").put("statement","李四是凶手").put("subjectEntityId",id(suspect)).put("predicate","IS_KILLER").put("validFromStoryTime",1).put("revealedAtStoryTime",20).put("status","ACTIVE"));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(detective)).put("factId",id(fact)).put("knowledgeState","KNOWN").put("knownFromStoryTime",20));
        assertThat(facts.resolve(id(project),10,List.of(id(detective),id(suspect))).path("facts")).isEmpty();
        JsonNode after=facts.resolve(id(project),21,List.of(id(detective),id(suspect)));
        assertThat(after.path("facts")).hasSize(1);
        assertThat(after.path("facts").get(0).path("knownBy")).extracting(node->node.asText()).containsExactly(id(detective));
    }

    @Test void resolvesAudienceCharacterAndHiddenKnowledgeWithoutFutureLeakage(){
        ObjectNode project=store.create(PROJECT,obj().put("name","知情差异").put("idea","观众晚于侦探得知真相"));
        ObjectNode detective=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","侦探")),partner=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","搭档"));
        ObjectNode truth=studio.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","killer").put("statement","管家是真凶").put("predicate","IS_KILLER").put("validFromStoryTime",1).put("revealedAtStoryTime",30).put("narrativeRole","HIDDEN_TRUTH"));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(detective)).put("factId",id(truth)).put("knowledgeState","KNOWN").put("knownFromStoryTime",10));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(partner)).put("factId",id(truth)).put("knowledgeState","SUSPECTED").put("knownFromStoryTime",20));

        JsonNode at25=facts.resolve(id(project),25,List.of(id(detective),id(partner)));
        assertThat(at25.path("audienceKnowledge")).isEmpty();
        assertThat(at25.path("hiddenTruths")).extracting(node->node.path("id").asText()).containsExactly(id(truth));
        assertThat(at25.path("characterKnowledge").path(id(detective)).path("knownFacts")).extracting(JsonNode::asText).containsExactly(id(truth));
        assertThat(at25.path("characterKnowledge").path(id(partner)).path("suspicions")).extracting(JsonNode::asText).containsExactly(id(truth));
        assertThat(at25.path("facts").get(0).path("knownBy")).extracting(JsonNode::asText).containsExactly(id(detective));
        assertThat(facts.resolve(id(project),31,List.of(id(detective),id(partner))).path("audienceKnowledge")).hasSize(1);
    }

    @Test void factMutationChangesFutureTruthWithoutRewritingHistoricalShots(){
        ObjectNode project=store.create(PROJECT,obj().put("name","事实变更").put("idea","证词后期被推翻"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","讯问室"));ObjectNode beat=store.create(BEAT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","证词被推翻"));
        ObjectNode detective=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","侦探"));
        ObjectNode fact=studio.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","alibi").put("statement","嫌疑人有不在场证明").put("predicate","HAS_ALIBI").put("validFromStoryTime",1));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(detective)).put("factId",id(fact)).put("knowledgeState","KNOWN").put("knownFromStoryTime",1));
        ObjectNode before=obj().put("statement","嫌疑人有不在场证明").put("predicate","HAS_ALIBI").put("status","ACTIVE"),after=before.deepCopy().put("statement","不在场证明已被推翻").put("predicate","ALIBI_DISPROVED");
        ObjectNode mutation=obj().put("projectId",id(project)).put("factId",id(fact)).put("operation","REVISE").put("effectiveFromStoryTime",50).put("reason","监控时间戳证明证词造假").put("sceneId",id(scene)).put("beatId",id(beat));mutation.set("before",before);mutation.set("after",after);studio.create(STORY_FACT_MUTATION,mutation);

        assertThat(facts.resolve(id(project),10,List.of(id(detective))).path("facts").get(0).path("statement").asText()).isEqualTo("嫌疑人有不在场证明");
        JsonNode future=facts.resolve(id(project),60,List.of(id(detective))).path("facts").get(0);
        assertThat(future.path("statement").asText()).isEqualTo("不在场证明已被推翻");
        assertThat(future.path("factVersion").asInt()).isEqualTo(2);
        assertThatThrownBy(()->studio.update(STORY_FACT,id(fact),obj().put("revision",revision(fact)).put("statement","直接覆盖历史")))
            .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("STORY_FACT_IMMUTABLE"));
    }

    @Test void factMutationRequiresAuditableBeforeAfterReasonSceneAndBeat(){
        ObjectNode project=store.create(PROJECT,obj().put("name","事实审计").put("idea","所有真相变化必须可追溯"));
        ObjectNode fact=studio.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","door").put("statement","门关闭").put("predicate","DOOR_STATE").put("value","CLOSED").put("validFromStoryTime",1));
        ObjectNode incomplete=obj().put("projectId",id(project)).put("factId",id(fact)).put("operation","REVISE").put("effectiveFromStoryTime",20);incomplete.set("changes",obj().put("value","OPEN"));
        assertThatThrownBy(()->studio.create(STORY_FACT_MUTATION,incomplete)).hasMessageContaining("before").hasMessageContaining("after").hasMessageContaining("reason").hasMessageContaining("sceneId").hasMessageContaining("beatId");
    }
}
