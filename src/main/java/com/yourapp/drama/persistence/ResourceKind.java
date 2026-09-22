package com.yourapp.drama.persistence;

import java.util.Arrays;
import java.util.Locale;

/** Closed resource registry: table names never come from request input. */
public enum ResourceKind {
    PROJECT("project", "projects", null, null),
    STORY_BIBLE("story_bible", "story-bibles", null, null),
    STORY_DOCUMENT("story_document", "story-documents", null, null),
    STORY_FACT("story_fact", "story-facts", null, null),
    STORY_FACT_MUTATION("story_fact_mutation", "story-fact-mutations", STORY_FACT, "factId"),
    CHARACTER_KNOWLEDGE("character_knowledge", "character-knowledge", null, null),
    ENTITY_ALIAS("entity_alias", "entity-aliases", null, null),
    RELATIONSHIP("relationship", "relationships", null, null),
    EPISODE("episode", "episodes", null, null),
    SCENE("scene", "scenes", EPISODE, "episodeId"),
    BEAT("beat", "beats", SCENE, "sceneId"),
    SHOT("shot", "shots", SCENE, "sceneId"),
    CHARACTER("character", "characters", null, null),
    CHARACTER_STATE("character_state", "character-states", CHARACTER, "characterId"),
    CHARACTER_PROVIDER_ASSET("character_provider_asset", "character-provider-assets", CHARACTER, "characterId"),
    CHARACTER_LOOK("character_look", "character-looks", CHARACTER, "characterId"),
    LOCATION("location", "locations", null, null),
    LOCATION_STATE("location_state", "location-states", LOCATION, "locationId"),
    PROP("prop", "props", null, null),
    PROP_STATE("prop_state", "prop-states", PROP, "propId"),
    ASSET_VIEW("asset_view", "asset-views", null, null),
    STORYBOARD("storyboard", "storyboards", SHOT, "shotId"),
    KEYFRAME("keyframe", "keyframes", SHOT, "shotId"),
    VIDEO_TAKE("video_take", "video-takes", SHOT, "shotId"),
    DIALOGUE_LINE("dialogue_line", "dialogue-lines", SHOT, "shotId"),
    VOICE_PROFILE("voice_profile", "voice-profiles", null, null),
    VOICE_STATE("voice_state", "voice-states", VOICE_PROFILE, "voiceProfileId"),
    AUDIO_CLIP("audio_clip", "audio-clips", null, null),
    GENERATION_JOB("generation_job", "jobs", null, null),
    QC_RESULT("qc_result", "qc-results", null, null),
    PROMPT_TEMPLATE("prompt_template", "prompt-templates", null, null),
    PROMPT_VERSION("prompt_version", "prompt-versions", null, null),
    COST_RECORD("cost_record", "cost-records", null, null),
    PRICE_SNAPSHOT("price_snapshot", "price-snapshots", null, null),
    HUMAN_EDIT_FEEDBACK("human_edit_feedback", "human-edit-feedback", null, null),
    RULE_EXPERIMENT("rule_experiment", "rule-experiments", null, null),
    PIPELINE_RUN("pipeline_run", "pipeline-runs", null, null),
    STAGE_RUN("stage_run", "stage-runs", PIPELINE_RUN, "pipelineRunId"),
    TIMELINE("timeline", "timelines", EPISODE, "episodeId"),
    TIMELINE_ITEM("timeline_item", "timeline-items", TIMELINE, "timelineId"),
    DIALECT_DICTIONARY("dialect_dictionary", "dialect-dictionaries", null, null),
    DIALECT_PHRASE("dialect_phrase", "dialect-phrases", null, null),
    DIALECT_EXAMPLE("dialect_example", "dialect-examples", null, null),
    DIALECT_CORRECTION("dialect_correction", "dialect-corrections", null, null);

    private final String table;
    private final String path;
    private final ResourceKind parent;
    private final String parentField;
    ResourceKind(String table, String path, ResourceKind parent, String parentField) {
        this.table = table; this.path = path; this.parent = parent; this.parentField = parentField;
    }
    public String table() { return table; }
    public String path() { return path; }
    public ResourceKind parentKind() { return parent; }
    public String parentField() { return parentField; }
    public boolean immutable() { return this == STORY_FACT_MUTATION || this == PROMPT_VERSION || this == COST_RECORD || this == PRICE_SNAPSHOT || this == HUMAN_EDIT_FEEDBACK || this == QC_RESULT; }
    public static ResourceKind fromPath(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(k -> k.path.equals(normalized)
                || k.table.replace('_', '-').equals(normalized)
                || (k == GENERATION_JOB && normalized.equals("generation-jobs")))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown resource: " + value));
    }
}
