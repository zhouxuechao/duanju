package com.yourapp.drama.production;

import java.util.List;

/** Declares exactly what a reference may control and what it must never transfer. */
public enum ReferenceAuthority {
    USER_SPECIFIED(120, List.of("explicitUserIntent"), List.of()),
    CHARACTER_IDENTITY(100, List.of("characterIdentity", "face", "hair", "body", "apparentAge", "distinctiveFeatures"), List.of("wardrobe", "camera", "pose", "background", "locationIdentity", "action", "composition", "motion")),
    CHARACTER_LOOK(96, List.of("wardrobe", "makeup", "hairState", "accessories", "currentLook"), List.of("characterIdentity", "locationIdentity", "composition")),
    LOCATION_IDENTITY(94, List.of("locationIdentity", "architecture", "layout", "material", "persistentEnvironment", "spatialTopology", "fixedFeatures"), List.of("characterIdentity", "characterFace", "wardrobe", "cameraMotion", "propIdentity")),
    PROP_IDENTITY(92, List.of("propIdentity", "propAppearance", "geometry", "logoOrMarking", "material", "gripStructure"), List.of("characterIdentity", "locationIdentity", "composition", "holder", "hand", "position", "state")),
    START_FRAME(90, List.of("openingComposition", "openingPose", "openingState"), List.of("futureAction", "futureState")),
    END_FRAME(89, List.of("endingComposition", "endingPose", "endingState"), List.of("openingState", "intermediateMotion")),
    INTERMEDIATE_KEYFRAME(88, List.of("timedPose", "timedComposition"), List.of("characterIdentity", "locationIdentity")),
    STORYBOARD(84, List.of("shotOrder", "composition", "blocking", "relativePosition", "roughPose", "cameraFraming"), List.of("characterIdentity", "wardrobe", "locationIdentity", "propIdentity")),
    COMPOSITION_REFERENCE(82, List.of("composition", "camera", "blocking", "relativePosition", "roughPose", "cameraFraming"), List.of("characterIdentity", "wardrobe", "locationIdentity", "propIdentity")),
    MOTION_REFERENCE(78, List.of("motion", "actionRhythm", "cameraMotion", "timing", "trajectory", "motionPhase"), List.of("characterIdentity", "wardrobe", "locationIdentity", "propIdentity")),
    AUDIO_REFERENCE(76, List.of("audioTiming", "audioRhythm"), List.of("visualIdentity")),
    VOICE_IDENTITY(80, List.of("voiceIdentity", "speakerTimbre"), List.of("visualIdentity", "musicStyle")),
    MUSIC_STYLE(65, List.of("musicStyle"), List.of("voiceIdentity", "visualIdentity")),
    RHYTHM_REFERENCE(64, List.of("rhythm"), List.of("voiceIdentity", "visualIdentity")),
    SFX_REFERENCE(63, List.of("soundEffect"), List.of("voiceIdentity", "musicStyle")),
    WHITE_MODEL_REFERENCE(60, List.of("geometry", "depth", "cameraPath"), List.of("surfaceAppearance", "characterIdentity")),
    PREVIOUS_TAKE(86, List.of("openingPose", "position", "motionPhase"), List.of("characterIdentity", "wardrobe", "locationIdentity", "propIdentity")),
    PREVIOUS_LAST_FRAME(87, List.of("openingComposition", "openingPose"), List.of("futureAction", "futureState"));

    private final int priority;
    private final List<String> controls;
    private final List<String> mustNotTransfer;
    ReferenceAuthority(int priority,List<String> controls,List<String> mustNotTransfer){this.priority=priority;this.controls=List.copyOf(controls);this.mustNotTransfer=List.copyOf(mustNotTransfer);}
    public int priority(){return priority;}
    public List<String> controls(){return controls;}
    public List<String> mustNotTransfer(){return mustNotTransfer;}

    public static ReferenceAuthority fromRole(ReferenceBinding.Role role){
        return switch(role){
            case CHARACTER_IDENTITY -> CHARACTER_IDENTITY;
            case CHARACTER_LOOK -> CHARACTER_LOOK;
            case LOCATION, LOCATION_LAYOUT, LOCATION_IDENTITY -> LOCATION_IDENTITY;
            case PROP, PROP_IDENTITY -> PROP_IDENTITY;
            case STORYBOARD -> STORYBOARD;
            case COMPOSITION_REFERENCE -> COMPOSITION_REFERENCE;
            case MOTION, MOTION_REFERENCE -> MOTION_REFERENCE;
            case AUDIO_REFERENCE -> AUDIO_REFERENCE;
            case VOICE_IDENTITY -> VOICE_IDENTITY;
            case MUSIC_STYLE -> MUSIC_STYLE;
            case RHYTHM_REFERENCE -> RHYTHM_REFERENCE;
            case SFX_REFERENCE -> SFX_REFERENCE;
            case WHITE_MODEL_REFERENCE -> WHITE_MODEL_REFERENCE;
            case START_FRAME, KEYFRAME_PROVIDER -> START_FRAME;
            case END_FRAME -> END_FRAME;
            case INTERMEDIATE_KEYFRAME -> INTERMEDIATE_KEYFRAME;
            case PREVIOUS_TAKE -> PREVIOUS_TAKE;
            case PREVIOUS_LAST_FRAME -> PREVIOUS_LAST_FRAME;
        };
    }
}
