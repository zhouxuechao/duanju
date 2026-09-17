package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
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
class RelationshipIntegrationTest {
    @Autowired DocumentStore store; @Autowired RelationshipResolver relationships;

    @Test void relationshipStateMustBeResolvedByStoryTime(){
        ObjectNode project=store.create(PROJECT,obj().put("name","关系时间").put("idea","群像"));
        ObjectNode a=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","甲"));
        ObjectNode b=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","乙"));
        store.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(a)).put("objectCharacterId",id(b)).put("relationshipType","ALLY").put("state","陌生人").put("validFromStoryTime",1).put("validToStoryTime",20));
        store.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(a)).put("objectCharacterId",id(b)).put("relationshipType","ALLY").put("state","朋友").put("validFromStoryTime",20));
        JsonNode early=relationships.resolve(id(project),10,List.of(id(a),id(b)));assertThat(early.path("relationships")).hasSize(1);assertThat(early.path("relationships").get(0).path("state").asText()).isEqualTo("陌生人");
        JsonNode late=relationships.resolve(id(project),30,List.of(id(a),id(b)));assertThat(late.path("relationships")).hasSize(1);assertThat(late.path("relationships").get(0).path("state").asText()).isEqualTo("朋友");
    }
}
