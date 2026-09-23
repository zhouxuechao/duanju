package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest @ActiveProfiles("test") @Transactional
class TemporalStateConflictTest {
    @Autowired StudioService studio;@Autowired DocumentStore store;@Autowired RelationshipResolver relationships;

    @Test void sameEntityIntervalsCannotOverlapOrStartAtTheSameStoryTime(){
        ObjectNode project=store.create(PROJECT,obj().put("name","时间冲突").put("idea","历史状态"));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","主角"));
        studio.create(CHARACTER_STATE,obj().put("projectId",id(project)).put("characterId",id(actor)).put("state","清醒").put("alive",true).put("validFromStoryTime",0).put("validToStoryTime",20));
        assertThatThrownBy(()->studio.create(CHARACTER_STATE,obj().put("projectId",id(project)).put("characterId",id(actor)).put("state","受伤").put("alive",true).put("validFromStoryTime",10).put("validToStoryTime",30)))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("时间区间");
    }

    @Test void distinctRelationshipTypesForTheSamePairRemainVisibleAtTheSameStoryTime(){
        ObjectNode project=store.create(PROJECT,obj().put("name","多重关系").put("idea","师徒也可能互相敌对"));
        ObjectNode first=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","甲")),second=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","乙"));
        studio.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(first)).put("objectCharacterId",id(second)).put("relationshipType","MENTORSHIP").put("state","师徒").put("validFromStoryTime",0));
        studio.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(first)).put("objectCharacterId",id(second)).put("relationshipType","CONFLICT").put("state","敌对").put("validFromStoryTime",0));

        assertThat(relationships.resolve(id(project),10,java.util.List.of(id(first),id(second))).path("relationships"))
                .extracting(value->value.path("relationshipType").asText()).containsExactlyInAnyOrder("MENTORSHIP","CONFLICT");
    }
    @Test void oppositeDirectionsOfTheSameRelationshipTypeRemainIndependent(){
        ObjectNode project=store.create(PROJECT,obj().put("name","有向关系").put("idea","同一种关系在两个人眼中方向不同"));
        ObjectNode first=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","甲")),second=store.create(CHARACTER,obj().put("projectId",id(project)).put("name","乙"));
        studio.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(first)).put("objectCharacterId",id(second)).put("relationshipType","TRUST").put("state","信任").put("validFromStoryTime",0));
        studio.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(second)).put("objectCharacterId",id(first)).put("relationshipType","TRUST").put("state","怀疑").put("validFromStoryTime",0));
        assertThat(relationships.resolve(id(project),10,java.util.List.of(id(first),id(second))).path("relationships"))
                .hasSize(2).extracting(value->value.path("state").asText()).containsExactlyInAnyOrder("信任","怀疑");
    }
}
