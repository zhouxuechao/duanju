package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticDependencyResolverTest {
    private final ObjectMapper mapper=new ObjectMapper();private final SemanticDependencyResolver resolver=new SemanticDependencyResolver();
    @Test void mapsFiveRealChangesToBoundedImpacts(){assertDecision("projects",obj("name","旧"),obj("name","新"),"NO_IMPACT","无须重做");assertDecision("dialogue-lines",obj("semanticText","旧话"),obj("semanticText","新话"),"REGENERATE","TTS");assertDecision("voice-profiles",obj("providerVoiceId","a"),obj("providerVoiceId","b"),"REGENERATE","TTS");assertDecision("timeline-items",obj("transition","CUT"),obj("transition","CROSS_DISSOLVE"),"REGENERATE","RENDER");assertDecision("location-states",obj("lighting","白天"),obj("lighting","夜晚"),"REVALIDATE","KEYFRAME");}
    @Test void subtitleOnlyChangeNeverInvalidatesTts(){ObjectNode decision=resolver.resolve("dialogue-lines","id",obj("subtitleText","旧字幕"),obj("subtitleText","新字幕"));assertThat(decision.path("action").asText()).isEqualTo("REVALIDATE");assertThat(decision.path("impacts").toString()).contains("SUBTITLE","TIMELINE").doesNotContain("TTS");}
    private void assertDecision(String kind,ObjectNode before,ObjectNode after,String action,String target){ObjectNode decision=resolver.resolve(kind,"id",before,after);assertThat(decision.path("action").asText()).isEqualTo(action);assertThat(decision.path("explain").asText()).contains(target);}
    private ObjectNode obj(String key,String value){return mapper.createObjectNode().put(key,value);}
}
