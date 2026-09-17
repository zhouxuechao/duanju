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
class EntityResolverTest {
    @Autowired DocumentStore store; @Autowired EntityResolver resolver;
    @Test void explicitAliasesResolveWithoutMergingSameNameCharacters() {
        ObjectNode project=store.create(PROJECT,obj().put("name","实体解析").put("idea","悬疑"));
        ObjectNode twinA=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","张伟"));
        ObjectNode twinB=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","张伟"));
        store.create(ENTITY_ALIAS,obj().put("projectId",id(project)).put("entityId",id(twinA)).put("alias","老张").put("aliasType","NICKNAME").put("validFromStoryTime",1));
        store.create(ENTITY_ALIAS,obj().put("projectId",id(project)).put("entityId",id(twinA)).put("alias","张老板").put("aliasType","TITLE").put("validFromStoryTime",1));
        JsonNode alias=resolver.resolve(id(project),"老张",10).path("matches"); assertThat(alias).hasSize(1).extracting(n->n.path("entityId").asText()).containsExactly(id(twinA));
        JsonNode sameName=resolver.resolve(id(project),"张伟",10).path("matches"); assertThat(sameName).hasSize(2); assertThat(sameName).allMatch(n->n.path("ambiguous").asBoolean());
    }
}
