package com.yourapp.drama.production;

/** Native audio is opt-in. Existing TTS/LipSync/PostProduction remains authoritative by default. */
public enum AudioGenerationPolicy { POST_ONLY, NATIVE_ONLY, NATIVE_PLUS_POST }
