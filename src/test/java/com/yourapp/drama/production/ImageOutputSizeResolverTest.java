package com.yourapp.drama.production;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ImageOutputSizeResolverTest {
    private final ImageOutputSizeResolver resolver=new ImageOutputSizeResolver(new ProviderCapabilityRegistry());

    @ParameterizedTest @ValueSource(strings={"9:16","16:9","1:1"})
    void staticSeedreamProfileKeepsTheRatioAsIntentWithoutInventingPixels(String ratio){
        ImageOutputProfile output=resolver.resolve(ProviderCapabilityRegistry.SEEDREAM_50,"2K",ratio);
        assertThat(output.imageQuality()).isEqualTo("2K");
        assertThat(output.aspectRatioIntent()).isEqualTo(ratio);
        assertThat(output.providerSize()).isEqualTo("2K").doesNotContain("x");
        assertThat(output.providerAspectRatioVerified()).isFalse();
        assertThat(output.verificationStatus()).isEqualTo("STATIC_UNVERIFIED");
    }
}
