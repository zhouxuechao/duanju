package com.yourapp.drama.production;

/** Stable task taxonomy used before a provider route is selected. */
public enum VideoTaskType {
    REFERENCE_GENERATE(ProviderTaskLockMode.UNLOCKED, true),
    FIRST_FRAME_GENERATE(ProviderTaskLockMode.LOCKED, true),
    FIRST_LAST_FRAME_GENERATE(ProviderTaskLockMode.LOCKED, true),
    KEYFRAME_GENERATE(ProviderTaskLockMode.UNLOCKED, true),
    STORYBOARD_GUIDED(ProviderTaskLockMode.UNLOCKED, true),
    GENERATE(ProviderTaskLockMode.UNLOCKED, false),
    WHITE_MODEL_RENDER(ProviderTaskLockMode.UNLOCKED, false),
    VIDEO_EDIT(ProviderTaskLockMode.LOCKED, false),
    AUDIO_EDIT(ProviderTaskLockMode.LOCKED, false),
    EXTEND_FORWARD(ProviderTaskLockMode.LOCKED, false),
    EXTEND_BACKWARD(ProviderTaskLockMode.LOCKED, false);

    private final ProviderTaskLockMode lockMode;
    private final boolean implemented;
    VideoTaskType(ProviderTaskLockMode lockMode, boolean implemented) { this.lockMode=lockMode; this.implemented=implemented; }
    public ProviderTaskLockMode lockMode(){ return lockMode; }
    public boolean implemented(){ return implemented; }
}
