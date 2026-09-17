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
class LocationStateResolverTest {
    @Autowired DocumentStore store; @Autowired LocationStateResolver states;
    @Test void locationStateIsReconstructedByStoryTimeAcrossScenes() {
        ObjectNode project=store.create(PROJECT,obj().put("name","地点状态").put("idea","悬疑"));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",id(project)).put("name","张家客厅"));
        store.create(LOCATION_STATE,obj().put("projectId",id(project)).put("locationId",id(location)).put("state","完整").put("validFromStoryTime",1));
        store.create(LOCATION_STATE,obj().put("projectId",id(project)).put("locationId",id(location)).put("state","燃烧中").put("validFromStoryTime",45));
        store.create(LOCATION_STATE,obj().put("projectId",id(project)).put("locationId",id(location)).put("state","废墟").put("validFromStoryTime",60));
        assertThat(states.resolve(id(project),id(location),10).path("state").asText()).isEqualTo("完整");
        assertThat(states.resolve(id(project),id(location),50).path("state").asText()).isEqualTo("燃烧中");
        assertThat(states.resolve(id(project),id(location),80).path("state").asText()).isEqualTo("废墟");
    }
}
