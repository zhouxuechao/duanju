package com.yourapp.drama.production;

/** Meaning of a still image in a video request; semantic references are not provider boundary slots. */
public enum KeyframeSemanticRole {
    START_FRAME(true), END_FRAME(true),
    INTERMEDIATE_KEYFRAME(false), COMPOSITION_REFERENCE(false), IDENTITY_REFERENCE(false),
    LOCATION_REFERENCE(false), PROP_REFERENCE(false), POSE_REFERENCE(false), STYLE_REFERENCE(false),
    STORYBOARD_GRID(false);
    private final boolean nativeBoundary;
    KeyframeSemanticRole(boolean nativeBoundary){this.nativeBoundary=nativeBoundary;}
    public boolean nativeBoundary(){return nativeBoundary;}
    public static KeyframeSemanticRole from(String value){try{return valueOf(value==null?"":value.trim().toUpperCase());}catch(IllegalArgumentException e){throw new IllegalArgumentException("KEYFRAME_SEMANTIC_ROLE_INVALID: "+value);}}
}
