package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAuthorityTest {
    @Test void identityAndLocationBindingsControlDifferentDimensions() {
        assertThat(ReferenceAuthority.CHARACTER_IDENTITY.controls()).contains("characterIdentity");
        assertThat(ReferenceAuthority.CHARACTER_IDENTITY.mustNotTransfer()).contains("wardrobe", "locationIdentity");
        assertThat(ReferenceAuthority.LOCATION_IDENTITY.controls()).contains("locationIdentity", "spatialTopology");
        assertThat(ReferenceAuthority.LOCATION_IDENTITY.mustNotTransfer()).contains("characterIdentity");
        assertThat(ReferenceAuthority.USER_SPECIFIED.priority()).isGreaterThan(ReferenceAuthority.CHARACTER_IDENTITY.priority());
        assertThat(ReferenceAuthority.CHARACTER_IDENTITY.controls()).contains("hair", "apparentAge", "distinctiveFeatures");
        assertThat(ReferenceAuthority.PROP_IDENTITY.mustNotTransfer()).contains("holder", "hand", "position", "state");
        assertThat(ReferenceAuthority.MOTION_REFERENCE.controls()).contains("cameraMotion", "trajectory");
    }

    @Test void canonicalRolesIncludeAudioAndKeyframeSemantics() {
        assertThat(ReferenceBinding.Role.from("voice_identity")).isEqualTo(ReferenceBinding.Role.VOICE_IDENTITY);
        assertThat(ReferenceBinding.Role.from("start_frame")).isEqualTo(ReferenceBinding.Role.START_FRAME);
        assertThat(ReferenceBinding.Role.from("storyboard")).isEqualTo(ReferenceBinding.Role.STORYBOARD);
    }
}
