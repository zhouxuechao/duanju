package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTaskLockModeTest {
    @Test void nativeFrameTasksAreLockedWhileSemanticReferenceTasksAreUnlocked() {
        assertThat(VideoTaskType.FIRST_FRAME_GENERATE.lockMode()).isEqualTo(ProviderTaskLockMode.LOCKED);
        assertThat(VideoTaskType.FIRST_LAST_FRAME_GENERATE.lockMode()).isEqualTo(ProviderTaskLockMode.LOCKED);
        assertThat(VideoTaskType.KEYFRAME_GENERATE.lockMode()).isEqualTo(ProviderTaskLockMode.UNLOCKED);
        assertThat(VideoTaskType.REFERENCE_GENERATE.lockMode()).isEqualTo(ProviderTaskLockMode.UNLOCKED);
    }
}
