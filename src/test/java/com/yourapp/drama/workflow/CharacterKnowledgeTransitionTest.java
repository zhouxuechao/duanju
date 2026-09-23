package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest @ActiveProfiles("test") @Transactional
class CharacterKnowledgeTransitionTest {
    @Autowired StudioService studio;@Autowired DocumentStore store;@Autowired StoryFactResolver resolver;

    @Test void transitionsCloseTheOpenIntervalAndKeepEveryHistoricalStateReplayable(){
        Fixture f=fixture();
        transition(f,"UNKNOWN",10,null,"尚未获知");
        transition(f,"SUSPECTED",20,null,"发现矛盾线索");
        transition(f,"MISUNDERSTOOD",30,"误以为管家是真凶","被伪证误导");
        transition(f,"KNOWN",50,null,"找到决定性证据");

        List<ObjectNode> history=store.list(CHARACTER_KNOWLEDGE,id(f.project),null).stream()
                .filter(value->id(f.actor).equals(text(value,"characterId"))&&id(f.fact).equals(text(value,"factId")))
                .sorted(Comparator.comparingDouble(value->value.path("validFromStoryTime").asDouble()))
                .toList();
        assertThat(history).extracting(value->text(value,"knowledgeState")).containsExactly("UNKNOWN","SUSPECTED","MISUNDERSTOOD","KNOWN");
        assertThat(history).extracting(value->value.path("validToStoryTime").isNumber()?value.path("validToStoryTime").asInt():null)
                .containsExactly(20,30,50,null);
        assertBucket(f,15,"unknownFacts");assertBucket(f,25,"suspicions");assertBucket(f,40,"misunderstandings");assertBucket(f,60,"knownFacts");
        assertThat(text(history.get(1),"previousKnowledgeId")).isEqualTo(id(history.get(0)));
        assertThat(text(history.get(2),"transitionReason")).isEqualTo("被伪证误导");
    }

    @Test void rejectsOutOfOrderSameTimeAndMisunderstoodWithoutBeliefWhileOrdinaryUpdateStaysClosed(){
        Fixture f=fixture();ObjectNode current=transition(f,"UNKNOWN",10,null,"尚未获知");transition(f,"SUSPECTED",20,null,"发现线索");
        assertThatThrownBy(()->transition(f,"KNOWN",15,null,"回写过去")).isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("KNOWLEDGE_TRANSITION_OUT_OF_ORDER"));
        assertThatThrownBy(()->transition(f,"KNOWN",20,null,"同一时间")).isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("KNOWLEDGE_TIME_CONFLICT"));
        assertThatThrownBy(()->transition(f,"MISUNDERSTOOD",30,null,"缺少误解内容")).hasMessageContaining("believedStatement");
        assertThatThrownBy(()->studio.update(CHARACTER_KNOWLEDGE,id(current),current.deepCopy().put("revision",revision(current)).put("validToStoryTime",20)))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("CHARACTER_KNOWLEDGE_IMMUTABLE"));
    }

    private ObjectNode transition(Fixture f,String state,double at,String belief,String reason){ObjectNode request=obj().put("projectId",id(f.project)).put("characterId",id(f.actor)).put("factId",id(f.fact)).put("newKnowledgeState",state).put("effectiveFromStoryTime",at).put("sourceSceneId",id(f.scene)).put("sourceBeatId",id(f.beat)).put("reason",reason);if(belief!=null)request.put("believedStatement",belief);return studio.transitionCharacterKnowledge(request);}
    private void assertBucket(Fixture f,double at,String bucket){JsonNode result=resolver.resolve(id(f.project),at,List.of(id(f.actor)));assertThat(result.path("characterKnowledge").path(id(f.actor)).path(bucket)).isNotEmpty();}
    private Fixture fixture(){ObjectNode project=store.create(PROJECT,obj().put("name","知识迁移").put("idea","逐步揭晓真相"));ObjectNode actor=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","侦探"));ObjectNode fact=studio.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","killer").put("statement","账房是真凶").put("predicate","IS_KILLER").put("validFromStoryTime",0));ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","祠堂"));ObjectNode beat=store.create(BEAT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("purpose","揭晓线索"));return new Fixture(project,actor,fact,scene,beat);}
    private record Fixture(ObjectNode project,ObjectNode actor,ObjectNode fact,ObjectNode scene,ObjectNode beat){}
}
