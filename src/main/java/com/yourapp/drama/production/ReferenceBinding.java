package com.yourapp.drama.production;

import java.util.Locale;

/** Canonical roles used between prompt compilation and provider request routing. */
public final class ReferenceBinding {
    private ReferenceBinding() {}

    public enum Role {
        CHARACTER_IDENTITY,
        CHARACTER_LOOK,
        LOCATION_IDENTITY,
        LOCATION,
        LOCATION_LAYOUT,
        PROP_IDENTITY,
        PROP,
        STORYBOARD,
        COMPOSITION_REFERENCE,
        MOTION_REFERENCE,
        AUDIO_REFERENCE,
        VOICE_IDENTITY,
        MUSIC_STYLE,
        RHYTHM_REFERENCE,
        SFX_REFERENCE,
        WHITE_MODEL_REFERENCE,
        START_FRAME,
        END_FRAME,
        INTERMEDIATE_KEYFRAME,
        KEYFRAME_PROVIDER,
        PREVIOUS_TAKE,
        PREVIOUS_LAST_FRAME,
        MOTION;

        public static Role from(String raw) {
            if (raw == null || raw.isBlank()) throw unsupported(raw);
            String value = raw.trim().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "PREVIOUS_TAKE_REFERENCE", "PREVIOUS_LOCKED_TAKE", "PREVIOUS_VIDEO" -> PREVIOUS_TAKE;
                case "CHARACTER" -> CHARACTER_IDENTITY;
                case "LOCATION_REFERENCE" -> LOCATION_IDENTITY;
                case "PROP_REFERENCE" -> PROP_IDENTITY;
                case "KEYFRAME" -> INTERMEDIATE_KEYFRAME;
                default -> {
                    try { yield Role.valueOf(value); }
                    catch (IllegalArgumentException failure) { throw unsupported(raw); }
                }
            };
        }

        private static IllegalArgumentException unsupported(String raw) {
            return new IllegalArgumentException("REFERENCE_ROLE_UNSUPPORTED: " + raw);
        }
    }
}
