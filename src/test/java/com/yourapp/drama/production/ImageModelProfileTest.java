package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageModelProfileTest {
    private final ProviderCapabilityRegistry registry=new ProviderCapabilityRegistry();

    @Test void seedreamFiveHasItsOwnExplicitStaticProfile(){
        ImageModelProfile profile=registry.imageProfile(ProviderCapabilityRegistry.SEEDREAM_50);
        assertThat(profile.family()).isEqualTo("SEEDREAM_5_0");
        assertThat(profile.verificationStatus()).isEqualTo("STATIC_UNVERIFIED");
        assertThat(profile.defaultSize()).isEqualTo("2K");
        assertThat(profile.supportedSizes()).containsExactly("2K");
        assertThat(profile.toJson().path("modelId").asText()).isEqualTo(ProviderCapabilityRegistry.SEEDREAM_50);
    }

    @Test void unknownImageModelFailsClosedInsteadOfBorrowingSeedreamCapabilities(){
        assertThatThrownBy(()->registry.imageProfile("future-model")).hasMessageContaining("UNVERIFIED_PROVIDER_MODEL");
    }
}
