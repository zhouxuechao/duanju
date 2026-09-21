package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoModelProfileTest {
    @Test void seedance20And25HaveSeparateCapabilitiesAndFingerprints() {
        ProviderCapabilityRegistry registry=new ProviderCapabilityRegistry();
        VideoModelProfile old=registry.profile("doubao-seedance-2-0-pro"),current=registry.profile("doubao-seedance-2-5-pro");
        assertThat(old.family()).isEqualTo("SEEDANCE_2_0");
        assertThat(current.family()).isEqualTo("SEEDANCE_2_5");
        assertThat(old.capabilityFingerprint()).isNotEqualTo(current.capabilityFingerprint());
        assertThat(old.supportsSemanticKeyframes()).isFalse();
        assertThat(current.supportsSemanticKeyframes()).isTrue();
        assertThat(current.supportsVideoEdit()).isTrue();
        assertThat(current.supportsExtension()).isTrue();
        assertThat(current.supportsWhiteModel()).isTrue();
        assertThat(VideoTaskType.VIDEO_EDIT.implemented()).isFalse();
        assertThat(current.hardLimits()).isEqualTo(new VideoModelProfile.ReferenceLimits(30,10,10,50));
        assertThat(current.maxReferenceVideoSeconds()).isEqualTo(30);
        assertThat(current.maxReferenceAudioSeconds()).isEqualTo(30);
        assertThat(current.supportedOutputFormats()).contains("mp4");
        assertThat(current.supportedDurations()).contains(30);
        assertThat(current.toJson().path("supportsReferenceImage").asBoolean()).isTrue();
    }
}
