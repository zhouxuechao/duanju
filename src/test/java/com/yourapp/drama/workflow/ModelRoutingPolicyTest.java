package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class ModelRoutingPolicyTest {
    @Test void routesWritingDirectorAndMediaWithoutSilentFallback(){MockEnvironment env=new MockEnvironment().withProperty("drama.provider.mode","volcengine").withProperty("drama.provider.volcengine.text-model","deepseek-writer").withProperty("drama.provider.volcengine.director-model","doubao-director").withProperty("drama.provider.volcengine.image-model","seedream").withProperty("drama.provider.volcengine.video-model","seedance");ModelRoutingPolicy policy=new ModelRoutingPolicy(env);assertThat(policy.decide("SCRIPT","HIGH","QUALITY").path("plannedModel").asText()).isEqualTo("deepseek-writer");assertThat(policy.decide("DIRECTOR_PLAN","HIGH","QUALITY").path("plannedModel").asText()).isEqualTo("doubao-director");assertThat(policy.decide("KEYFRAME","MEDIUM","BALANCED").path("plannedModel").asText()).isEqualTo("seedream");assertThat(policy.decide("VIDEO","HIGH","QUALITY").path("fallbackChain")).isEmpty();}
}
