package com.yourapp.drama.production;

/** Minimum clip length accepted by the current timeline and final-composition chain. */
public final class EditorialTiming {
    public static final long MIN_SHOT_MS=1_250;
    public static final double MIN_SHOT_SECONDS=MIN_SHOT_MS/1_000d;
    private EditorialTiming(){}
}
