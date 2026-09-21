package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyframeSemanticRoleTest {
    @Test void distinguishesProviderBoundaryFramesFromSemanticReferences() {
        assertThat(KeyframeSemanticRole.START_FRAME.nativeBoundary()).isTrue();
        assertThat(KeyframeSemanticRole.END_FRAME.nativeBoundary()).isTrue();
        assertThat(KeyframeSemanticRole.INTERMEDIATE_KEYFRAME.nativeBoundary()).isFalse();
        assertThat(KeyframeSemanticRole.STORYBOARD_GRID.nativeBoundary()).isFalse();
    }
}
