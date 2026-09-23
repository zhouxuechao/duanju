package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.CHARACTER_KNOWLEDGE;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;
import static com.yourapp.drama.persistence.ResourceKind.STORY_FACT;
import static com.yourapp.drama.workflow.Documents.id;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CharacterKnowledgeTimelineTest {
    @Autowired DocumentStore store;
    @Autowired StudioService studio;
    @Autowired StoryFactResolver resolver;

    @Test void knowledgeAndMisunderstandingRemainReplayableAtEveryStoryTime() {
        ObjectNode project=store.create(PROJECT,obj().put("name","知识时间线").put("idea","真相逐步揭晓"));
        ObjectNode actor=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","女主"));
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","father")
                .put("statement","老板其实是男主父亲").put("predicate","IS_FATHER").put("validFromStoryTime",0).put("status","ACTIVE"));
        knowledge(project,actor,fact,"UNKNOWN",10,20,null);
        knowledge(project,actor,fact,"SUSPECTED",20,30,null);
        knowledge(project,actor,fact,"MISUNDERSTOOD",30,50,"老板害死了父亲");
        knowledge(project,actor,fact,"KNOWN",50,null,null);

        assertState(project,actor,15,"unknownFacts");
        assertState(project,actor,25,"suspicions");
        JsonNode at40=resolver.resolve(id(project),40,List.of(id(actor)));
        assertThat(at40.path("characterKnowledge").path(id(actor)).path("misunderstandings"))
                .extracting(JsonNode::asText).containsExactly(id(fact));
        assertThat(at40.path("misunderstandings").get(0).path("believedStatement").asText()).isEqualTo("老板害死了父亲");
        assertState(project,actor,60,"knownFacts");
        assertState(project,actor,15,"unknownFacts");
    }

    private void knowledge(ObjectNode project,ObjectNode actor,ObjectNode fact,String state,double from,Integer to,String believed) {
        ObjectNode value=obj().put("projectId",id(project)).put("characterId",id(actor)).put("factId",id(fact))
                .put("knowledgeState",state).put("validFromStoryTime",from);
        if(to!=null)value.put("validToStoryTime",to);
        if(believed!=null)value.put("believedStatement",believed);
        studio.create(CHARACTER_KNOWLEDGE,value);
    }

    private void assertState(ObjectNode project,ObjectNode actor,double time,String bucket) {
        assertThat(resolver.resolve(id(project),time,List.of(id(actor))).path("characterKnowledge").path(id(actor)).path(bucket))
                .isNotEmpty();
    }
}
