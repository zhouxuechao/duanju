package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.*;

class GenerationProfilePolicyTest {
    private final ProviderCapabilityRegistry capabilities=new ProviderCapabilityRegistry();
    private final GenerationProfilePolicy policy=new GenerationProfilePolicy(capabilities);

    @Test void testIsTheSafeDefaultAndStandardOnlyRaisesVideoResolution(){
        ObjectNode test=policy.applyDefaults(obj());
        assertThat(test.path("generationProfile").asText()).isEqualTo("TEST");
        assertThat(test.path("imageModel").asText()).isEqualTo(ProviderCapabilityRegistry.SEEDREAM_50);
        assertThat(test.path("videoModel").asText()).isEqualTo(ProviderCapabilityRegistry.SEEDANCE_20_FAST);
        assertThat(test.path("videoResolution").asText()).isEqualTo("480p");
        assertThat(test.path("imageSize").asText()).isEqualTo("2K");

        ObjectNode standard=policy.applyDefaults(obj().put("generationProfile","STANDARD"));
        assertThat(standard.path("videoResolution").asText()).isEqualTo("720p");
    }

    @Test void finalRequiresExplicitConfigurationAndFastProfileUsesRangeDurations(){
        assertThatThrownBy(()->policy.applyDefaults(obj().put("generationProfile","FINAL"))).hasMessageContaining("FINAL");
        ObjectNode configuredFinal=policy.applyDefaults(obj().put("generationProfile","FINAL").put("imageModel","future-image-model")
                .put("videoModel",ProviderCapabilityRegistry.SEEDANCE_20_FAST).put("videoResolution","720p").put("imageSize","4K"));
        assertThat(configuredFinal.path("imageModel").asText()).isEqualTo("future-image-model");
        assertThat(configuredFinal.path("imageSize").asText()).isEqualTo("4K");
        VideoModelProfile fast=capabilities.profile(ProviderCapabilityRegistry.SEEDANCE_20_FAST);
        assertThat(fast.family()).isEqualTo("SEEDANCE_2_0_FAST");
        assertThat(fast.supportedResolutions()).containsExactly("480p","720p");
        assertThat(fast.providerDuration(11)).isEqualTo(11);
        assertThatThrownBy(()->fast.requireResolution("1080p")).hasMessageContaining("RESOLUTION");
    }
}
