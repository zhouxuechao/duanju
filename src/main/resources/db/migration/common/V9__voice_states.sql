CREATE TABLE "voice_state" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    voice_profile_id UUID NOT NULL,
    state TEXT NOT NULL,
    provider_voice_id TEXT,
    reference_audio_url TEXT,
    valid_from_story_time NUMERIC,
    valid_to_story_time NUMERIC,
    CONSTRAINT uq_voice_state_project_id UNIQUE(project_id,id),
    CONSTRAINT ck_voice_state_parent CHECK(parent_id=voice_profile_id),
    CONSTRAINT ck_voice_state_range CHECK(valid_from_story_time IS NULL OR valid_to_story_time IS NULL OR valid_to_story_time>valid_from_story_time)
);
CREATE INDEX ix_voice_state_lookup ON "voice_state"(project_id,voice_profile_id,valid_from_story_time);
