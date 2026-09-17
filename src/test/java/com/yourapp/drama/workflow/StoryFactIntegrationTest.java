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
    @Autowired DocumentStore store; @Autowired StoryFactResolver facts;

    @Test void characterKnowledgeMustRespectStoryTimeAndCannotLeakWorldTruth(){
        ObjectNode project=store.create(PROJECT,obj().put("name","事实时间").put("idea","悬疑"));
        ObjectNode detective=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","张三"));
        ObjectNode suspect=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","李四"));
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","killer").put("statement","李四是凶手").put("subjectEntityId",id(suspect)).put("predicate","IS_KILLER").put("validFromStoryTime",1).put("revealedAtStoryTime",20).put("status","ACTIVE"));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(detective)).put("factId",id(fact)).put("knowledgeState","KNOWN").put("knownFromStoryTime",20));
        assertThat(facts.resolve(id(project),10,List.of(id(detective),id(suspect))).path("facts")).isEmpty();
        JsonNode after=facts.resolve(id(project),21,List.of(id(detective),id(suspect)));
        assertThat(after.path("facts")).hasSize(1);
        assertThat(after.path("facts").get(0).path("knownBy").asText()).isEqualTo(id(detective));
    }
}
