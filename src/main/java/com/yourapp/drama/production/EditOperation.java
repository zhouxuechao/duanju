package com.yourapp.drama.production;

/** Auditable editorial decisions stored on TimelineItem, the final-cut source of truth. */
public enum EditOperation {
    TRIM,
    CUT,
    REACTION_SHOT,
    INSERT_SHOT,
    J_CUT,
    L_CUT,
    AUDIO_BRIDGE,
    DIALOGUE_GAP,
    PAUSE,
    CLIP_REPLACE
}
