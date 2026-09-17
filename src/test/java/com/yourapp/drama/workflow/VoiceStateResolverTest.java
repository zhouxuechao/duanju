package com.yourapp.drama.workflow;

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

@SpringBootTest @ActiveProfiles("test") @Transactional
class VoiceStateResolverTest {
    @Autowired DocumentStore store; @Autowired VoiceStateResolver voices;
    @Test void voiceStateChangesByStoryTime() {
        ObjectNode project=store.create(PROJECT,obj().put("name","声音阶段").put("idea","身份伪装"));
        ObjectNode profile=store.create(VOICE_PROFILE,obj().put("projectId",id(project)).put("name","王强音色").put("characterId",id(store.create(ResourceKind.CHARACTER,obj().put("projectId",id(project)).put("name","王强")))));
        store.create(VOICE_STATE,obj().put("projectId",id(project)).put("voiceProfileId",id(profile)).put("state","CHILD").put("providerVoiceId","child").put("validFromStoryTime",1).put("validToStoryTime",18));
        store.create(VOICE_STATE,obj().put("projectId",id(project)).put("voiceProfileId",id(profile)).put("state","ADULT").put("providerVoiceId","adult").put("validFromStoryTime",18));
        assertThat(voices.resolve(id(project),id(profile),10).path("providerVoiceId").asText()).isEqualTo("child");
        assertThat(voices.resolve(id(project),id(profile),20).path("providerVoiceId").asText()).isEqualTo("adult");
    }
}
