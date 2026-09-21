package com.yourapp.drama.production;

import java.util.Locale;

/** Canonical roles used between prompt compilation and provider request routing. */
public final class ReferenceBinding {
    private ReferenceBinding() {}

    public enum Role {
        CHARACTER_LOOK,
        LOCATION,
        LOCATION_LAYOUT,
        PROP,
        COMPOSITION_REFERENCE,
        KEYFRAME_PROVIDER,
        PREVIOUS_TAKE,
        PREVIOUS_LAST_FRAME,
        MOTION;

        public static Role from(String raw) {
            if (raw == null || raw.isBlank()) throw unsupported(raw);
            String value = raw.trim().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "PREVIOUS_TAKE_REFERENCE", "PREVIOUS_LOCKED_TAKE", "PREVIOUS_VIDEO" -> PREVIOUS_TAKE;
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
