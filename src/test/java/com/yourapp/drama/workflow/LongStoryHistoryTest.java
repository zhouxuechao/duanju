package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class LongStoryHistoryTest {
    @Autowired DocumentStore store; @Autowired StoryFactResolver facts; @Autowired RelationshipResolver relationships; @Autowired LocationStateResolver locations;
    @Test void regeneratingEarlyShotReadsHistoricalWorldAfterLaterChanges() {
        ObjectNode project=store.create(PROJECT,obj().put("name","百镜头历史").put("idea","复杂群像"));
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",id(project)).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",id(project)).put("episodeId",id(episode)).put("name","主线"));
        ObjectNode a=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","甲")), b=store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","乙"));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",id(project)).put("name","客厅"));
        for(int i=1;i<=100;i++) store.create(SHOT,obj().put("projectId",id(project)).put("sceneId",id(scene)).put("shotNo",i).put("storyTime",i));
        store.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(a)).put("objectCharacterId",id(b)).put("relationshipType","ALLY").put("state","陌生").put("validFromStoryTime",1).put("validToStoryTime",60));
        store.create(RELATIONSHIP,obj().put("projectId",id(project)).put("subjectCharacterId",id(a)).put("objectCharacterId",id(b)).put("relationshipType","ALLY").put("state","敌对").put("validFromStoryTime",60));
        ObjectNode fact=store.create(STORY_FACT,obj().put("projectId",id(project)).put("factKey","betrayal").put("statement","甲背叛乙").put("predicate","BETRAYED").put("validFromStoryTime",80).put("status","ACTIVE"));
        store.create(CHARACTER_KNOWLEDGE,obj().put("projectId",id(project)).put("characterId",id(a)).put("factId",id(fact)).put("knowledgeState","KNOWN").put("knownFromStoryTime",80));
        store.create(LOCATION_STATE,obj().put("projectId",id(project)).put("locationId",id(location)).put("state","完整").put("validFromStoryTime",1));
        store.create(LOCATION_STATE,obj().put("projectId",id(project)).put("locationId",id(location)).put("state","废墟").put("validFromStoryTime",90));
        assertThat(relationships.resolve(id(project),10,List.of(id(a),id(b))).path("relationships").get(0).path("state").asText()).isEqualTo("陌生");
        assertThat(relationships.resolve(id(project),95,List.of(id(a),id(b))).path("relationships").get(0).path("state").asText()).isEqualTo("敌对");
        assertThat(facts.resolve(id(project),10,List.of(id(a))).path("facts")).isEmpty();
        assertThat(facts.resolve(id(project),85,List.of(id(a))).path("facts")).hasSize(1);
        assertThat(locations.resolve(id(project),id(location),10).path("state").asText()).isEqualTo("完整");
        assertThat(locations.resolve(id(project),id(location),95).path("state").asText()).isEqualTo("废墟");
    }
}
