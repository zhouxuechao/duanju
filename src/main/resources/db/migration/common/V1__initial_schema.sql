-- PostgreSQL production and H2 PostgreSQL-mode demo share this exact migration.
-- Full JSON documents are TEXT for cross-database portability. Safety fields have typed columns.
-- 25 core tables from development checklist + 4 dialect knowledge-base tables.

CREATE TABLE "project" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_project_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_project_self CHECK (project_id=id AND parent_id IS NULL)
);
CREATE INDEX ix_project_project_parent ON "project"(project_id,parent_id,created_at);

CREATE TABLE "story_bible" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_story_bible_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_story_bible_project_parent ON "story_bible"(project_id,parent_id,created_at);

CREATE TABLE "episode" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_episode_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_episode_project_parent ON "episode"(project_id,parent_id,created_at);

CREATE TABLE "scene" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    episode_id UUID NOT NULL,
    CONSTRAINT uq_scene_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_scene_parent CHECK (parent_id=episode_id)
);
CREATE INDEX ix_scene_project_parent ON "scene"(project_id,parent_id,created_at);

CREATE TABLE "shot" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    scene_id UUID NOT NULL,
    duration NUMERIC(6,3) NOT NULL CHECK (duration BETWEEN 2 AND 5),
    status VARCHAR(40) NOT NULL CHECK (status IN ('DRAFT','PLANNED','STORYBOARD_GENERATING','STORYBOARD_READY','STORYBOARD_LOCKED','KEYFRAME_GENERATING','KEYFRAME_READY','KEYFRAME_QC','KEYFRAME_LOCKED','VIDEO_GENERATING','VIDEO_READY','VIDEO_QC','VIDEO_LOCKED','AUDIO_READY','EDITED','FINISHED','FAILED','NEEDS_REPAIR','PROVIDER_URL_EXPIRED')),
    CONSTRAINT uq_shot_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_shot_parent CHECK (parent_id=scene_id)
);
CREATE INDEX ix_shot_project_parent ON "shot"(project_id,parent_id,created_at);

CREATE TABLE "character" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    provider VARCHAR(64),
    source_type VARCHAR(64),
    provider_asset_id TEXT,
    provider_status VARCHAR(40),
    identity_locked BOOLEAN NOT NULL,
    CONSTRAINT uq_character_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_character_project_parent ON "character"(project_id,parent_id,created_at);

CREATE TABLE "character_provider_asset" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    character_id UUID NOT NULL,
    provider VARCHAR(64),
    provider_asset_id TEXT,
    provider_status VARCHAR(40),
    CONSTRAINT uq_character_provider_asset_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_character_provider_asset_parent CHECK (parent_id=character_id)
);
CREATE INDEX ix_character_provider_asset_project_parent ON "character_provider_asset"(project_id,parent_id,created_at);

CREATE TABLE "character_look" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    character_id UUID NOT NULL,
    CONSTRAINT uq_character_look_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_character_look_parent CHECK (parent_id=character_id)
);
CREATE INDEX ix_character_look_project_parent ON "character_look"(project_id,parent_id,created_at);

CREATE TABLE "location" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_location_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_location_project_parent ON "location"(project_id,parent_id,created_at);

CREATE TABLE "location_look" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    location_id UUID NOT NULL,
    CONSTRAINT uq_location_look_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_location_look_parent CHECK (parent_id=location_id)
);
CREATE INDEX ix_location_look_project_parent ON "location_look"(project_id,parent_id,created_at);

CREATE TABLE "prop" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_prop_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_prop_project_parent ON "prop"(project_id,parent_id,created_at);

CREATE TABLE "prop_state" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    prop_id UUID NOT NULL,
    CONSTRAINT uq_prop_state_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_prop_state_parent CHECK (parent_id=prop_id)
);
CREATE INDEX ix_prop_state_project_parent ON "prop_state"(project_id,parent_id,created_at);

CREATE TABLE "storyboard" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    CONSTRAINT uq_storyboard_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_storyboard_parent CHECK (parent_id=shot_id),
    CONSTRAINT uq_storyboard_version UNIQUE(project_id,parent_id,version)
);
CREATE INDEX ix_storyboard_project_parent ON "storyboard"(project_id,parent_id,created_at);

CREATE TABLE "keyframe" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    provider VARCHAR(64) NOT NULL,
    source_model VARCHAR(255),
    provider_url TEXT NOT NULL CHECK (LENGTH(provider_url)>0),
    provider_url_expires_at TIMESTAMP WITH TIME ZONE,
    archive_url TEXT,
    generation_job_id UUID,
    provider_request_id TEXT,
    handoff_status VARCHAR(40) NOT NULL CHECK (handoff_status IN ('READY','HANDED_OFF','EXPIRED','INVALID')),
    qc_status VARCHAR(40) NOT NULL CHECK (qc_status IN ('PENDING','PASSED','FAILED')),
    selected BOOLEAN NOT NULL,
    locked BOOLEAN NOT NULL,
    CONSTRAINT uq_keyframe_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_keyframe_parent CHECK (parent_id=shot_id),
    CONSTRAINT uq_keyframe_version UNIQUE(project_id,parent_id,version)
);
CREATE INDEX ix_keyframe_project_parent ON "keyframe"(project_id,parent_id,created_at);

CREATE TABLE "video_take" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID NOT NULL,
    take_no INTEGER NOT NULL CHECK (take_no>0),
    provider VARCHAR(64),
    model VARCHAR(255),
    prompt_version_id UUID,
    provider_request_id TEXT,
    source_keyframe_id UUID NOT NULL,
    source_provider_url_snapshot TEXT NOT NULL CHECK (LENGTH(source_provider_url_snapshot)>0),
    video_url TEXT,
    archive_url TEXT,
    qc_score NUMERIC(6,3) CHECK (qc_score BETWEEN 0 AND 100),
    selected BOOLEAN NOT NULL,
    locked BOOLEAN NOT NULL,
    cost NUMERIC(18,8) NOT NULL CHECK (cost >= 0),
    CONSTRAINT uq_video_take_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_video_take_parent CHECK (parent_id=shot_id),
    CONSTRAINT uq_video_take_number UNIQUE(project_id,shot_id,take_no)
);
CREATE INDEX ix_video_take_project_parent ON "video_take"(project_id,parent_id,created_at);

CREATE TABLE "dialogue_line" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID NOT NULL,
    character_id UUID,
    display_text TEXT,
    dialect_text TEXT,
    speech_text TEXT,
    CONSTRAINT uq_dialogue_line_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_dialogue_line_parent CHECK (parent_id=shot_id)
);
CREATE INDEX ix_dialogue_line_project_parent ON "dialogue_line"(project_id,parent_id,created_at);

CREATE TABLE "voice_profile" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    character_id UUID,
    CONSTRAINT uq_voice_profile_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_voice_profile_project_parent ON "voice_profile"(project_id,parent_id,created_at);

CREATE TABLE "audio_clip" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID,
    dialogue_line_id UUID,
    CONSTRAINT uq_audio_clip_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_audio_clip_project_parent ON "audio_clip"(project_id,parent_id,created_at);

CREATE TABLE "generation_job" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID,
    type VARCHAR(40) NOT NULL CHECK (type IN ('STORY','CHARACTER_PLAN','CHARACTER_LOOK','LOCATION_LOOK','SCRIPT','SHOT_PLAN','STORYBOARD','KEYFRAME','KEYFRAME_QC','VIDEO','VIDEO_QC','REPAIR','TTS','LIPSYNC','TIMELINE','RENDER','ARCHIVE')),
    status VARCHAR(40) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED','CANCELLED','RETRY_WAIT')),
    provider_request_id TEXT,
    provider_task_id TEXT,
    prompt_version_id UUID,
    request_key VARCHAR(512),
    retry_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL CHECK (attempts>=0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts>0),
    progress NUMERIC(6,3) NOT NULL CHECK (progress BETWEEN 0 AND 100),
    cost NUMERIC(18,8) NOT NULL CHECK (cost>=0),
    failure_reason TEXT,
    cancel_requested BOOLEAN NOT NULL,
    CONSTRAINT uq_generation_job_project_id UNIQUE (project_id,id),
    CONSTRAINT uq_generation_job_request UNIQUE(project_id,request_key)
);
CREATE INDEX ix_generation_job_project_parent ON "generation_job"(project_id,parent_id,created_at);

CREATE TABLE "qc_result" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID,
    keyframe_id UUID,
    video_take_id UUID,
    generation_job_id UUID,
    CONSTRAINT uq_qc_result_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_qc_result_project_parent ON "qc_result"(project_id,parent_id,created_at);

CREATE TABLE "prompt_template" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_prompt_template_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_prompt_template_project_parent ON "prompt_template"(project_id,parent_id,created_at);

CREATE TABLE "prompt_version" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    shot_id UUID,
    version INTEGER NOT NULL CHECK (version>0),
    prompt_template_id UUID,
    CONSTRAINT uq_prompt_version_project_id UNIQUE (project_id,id),
    CONSTRAINT uq_prompt_version_version UNIQUE(project_id,parent_id,version)
);
CREATE INDEX ix_prompt_version_project_parent ON "prompt_version"(project_id,parent_id,created_at);

CREATE TABLE "cost_record" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    generation_job_id UUID,
    cost NUMERIC(18,8) NOT NULL CHECK (cost>=0),
    CONSTRAINT uq_cost_record_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_cost_record_project_parent ON "cost_record"(project_id,parent_id,created_at);

CREATE TABLE "timeline" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    episode_id UUID NOT NULL,
    CONSTRAINT uq_timeline_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_timeline_parent CHECK (parent_id=episode_id)
);
CREATE INDEX ix_timeline_project_parent ON "timeline"(project_id,parent_id,created_at);

CREATE TABLE "timeline_item" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    timeline_id UUID NOT NULL,
    shot_id UUID,
    video_take_id UUID,
    audio_clip_id UUID,
    CONSTRAINT uq_timeline_item_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_timeline_item_parent CHECK (parent_id=timeline_id)
);
CREATE INDEX ix_timeline_item_project_parent ON "timeline_item"(project_id,parent_id,created_at);

CREATE TABLE "dialect_dictionary" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_dialect_dictionary_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_dialect_dictionary_project_parent ON "dialect_dictionary"(project_id,parent_id,created_at);

CREATE TABLE "dialect_phrase" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_dialect_phrase_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_dialect_phrase_project_parent ON "dialect_phrase"(project_id,parent_id,created_at);

CREATE TABLE "dialect_example" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_dialect_example_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_dialect_example_project_parent ON "dialect_example"(project_id,parent_id,created_at);

CREATE TABLE "dialect_correction" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT uq_dialect_correction_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_dialect_correction_project_parent ON "dialect_correction"(project_id,parent_id,created_at);

-- Install references after all tables exist; composite FKs prohibit cross-project links.
ALTER TABLE "story_bible" ADD CONSTRAINT fk_story_bible_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "episode" ADD CONSTRAINT fk_episode_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "scene" ADD CONSTRAINT fk_scene_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "scene" ADD CONSTRAINT fk_scene_episode_id FOREIGN KEY (project_id,episode_id) REFERENCES "episode"(project_id,id);
ALTER TABLE "shot" ADD CONSTRAINT fk_shot_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "shot" ADD CONSTRAINT fk_shot_scene_id FOREIGN KEY (project_id,scene_id) REFERENCES "scene"(project_id,id);
ALTER TABLE "character" ADD CONSTRAINT fk_character_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "character_provider_asset" ADD CONSTRAINT fk_character_provider_asset_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "character_provider_asset" ADD CONSTRAINT fk_character_provider_asset_character_id FOREIGN KEY (project_id,character_id) REFERENCES "character"(project_id,id);
ALTER TABLE "character_look" ADD CONSTRAINT fk_character_look_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "character_look" ADD CONSTRAINT fk_character_look_character_id FOREIGN KEY (project_id,character_id) REFERENCES "character"(project_id,id);
ALTER TABLE "location" ADD CONSTRAINT fk_location_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "location_look" ADD CONSTRAINT fk_location_look_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "location_look" ADD CONSTRAINT fk_location_look_location_id FOREIGN KEY (project_id,location_id) REFERENCES "location"(project_id,id);
ALTER TABLE "prop" ADD CONSTRAINT fk_prop_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "prop_state" ADD CONSTRAINT fk_prop_state_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "prop_state" ADD CONSTRAINT fk_prop_state_prop_id FOREIGN KEY (project_id,prop_id) REFERENCES "prop"(project_id,id);
ALTER TABLE "storyboard" ADD CONSTRAINT fk_storyboard_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "storyboard" ADD CONSTRAINT fk_storyboard_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "keyframe" ADD CONSTRAINT fk_keyframe_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "keyframe" ADD CONSTRAINT fk_keyframe_generation_job_id FOREIGN KEY (project_id,generation_job_id) REFERENCES "generation_job"(project_id,id);
ALTER TABLE "keyframe" ADD CONSTRAINT fk_keyframe_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "video_take" ADD CONSTRAINT fk_video_take_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "video_take" ADD CONSTRAINT fk_video_take_prompt_version_id FOREIGN KEY (project_id,prompt_version_id) REFERENCES "prompt_version"(project_id,id);
ALTER TABLE "video_take" ADD CONSTRAINT fk_video_take_source_keyframe_id FOREIGN KEY (project_id,source_keyframe_id) REFERENCES "keyframe"(project_id,id);
ALTER TABLE "video_take" ADD CONSTRAINT fk_video_take_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "dialogue_line" ADD CONSTRAINT fk_dialogue_line_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "dialogue_line" ADD CONSTRAINT fk_dialogue_line_character_id FOREIGN KEY (project_id,character_id) REFERENCES "character"(project_id,id);
ALTER TABLE "dialogue_line" ADD CONSTRAINT fk_dialogue_line_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "voice_profile" ADD CONSTRAINT fk_voice_profile_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "voice_profile" ADD CONSTRAINT fk_voice_profile_character_id FOREIGN KEY (project_id,character_id) REFERENCES "character"(project_id,id);
ALTER TABLE "audio_clip" ADD CONSTRAINT fk_audio_clip_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "audio_clip" ADD CONSTRAINT fk_audio_clip_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "audio_clip" ADD CONSTRAINT fk_audio_clip_dialogue_line_id FOREIGN KEY (project_id,dialogue_line_id) REFERENCES "dialogue_line"(project_id,id);
ALTER TABLE "generation_job" ADD CONSTRAINT fk_generation_job_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "generation_job" ADD CONSTRAINT fk_generation_job_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "generation_job" ADD CONSTRAINT fk_generation_job_prompt_version_id FOREIGN KEY (project_id,prompt_version_id) REFERENCES "prompt_version"(project_id,id);
ALTER TABLE "qc_result" ADD CONSTRAINT fk_qc_result_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "qc_result" ADD CONSTRAINT fk_qc_result_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "qc_result" ADD CONSTRAINT fk_qc_result_keyframe_id FOREIGN KEY (project_id,keyframe_id) REFERENCES "keyframe"(project_id,id);
ALTER TABLE "qc_result" ADD CONSTRAINT fk_qc_result_video_take_id FOREIGN KEY (project_id,video_take_id) REFERENCES "video_take"(project_id,id);
ALTER TABLE "qc_result" ADD CONSTRAINT fk_qc_result_generation_job_id FOREIGN KEY (project_id,generation_job_id) REFERENCES "generation_job"(project_id,id);
ALTER TABLE "prompt_template" ADD CONSTRAINT fk_prompt_template_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "prompt_version" ADD CONSTRAINT fk_prompt_version_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "prompt_version" ADD CONSTRAINT fk_prompt_version_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "prompt_version" ADD CONSTRAINT fk_prompt_version_prompt_template_id FOREIGN KEY (project_id,prompt_template_id) REFERENCES "prompt_template"(project_id,id);
ALTER TABLE "cost_record" ADD CONSTRAINT fk_cost_record_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "cost_record" ADD CONSTRAINT fk_cost_record_generation_job_id FOREIGN KEY (project_id,generation_job_id) REFERENCES "generation_job"(project_id,id);
ALTER TABLE "timeline" ADD CONSTRAINT fk_timeline_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "timeline" ADD CONSTRAINT fk_timeline_episode_id FOREIGN KEY (project_id,episode_id) REFERENCES "episode"(project_id,id);
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_shot_id FOREIGN KEY (project_id,shot_id) REFERENCES "shot"(project_id,id);
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_video_take_id FOREIGN KEY (project_id,video_take_id) REFERENCES "video_take"(project_id,id);
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_audio_clip_id FOREIGN KEY (project_id,audio_clip_id) REFERENCES "audio_clip"(project_id,id);
ALTER TABLE "timeline_item" ADD CONSTRAINT fk_timeline_item_timeline_id FOREIGN KEY (project_id,timeline_id) REFERENCES "timeline"(project_id,id);
ALTER TABLE "dialect_dictionary" ADD CONSTRAINT fk_dialect_dictionary_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "dialect_phrase" ADD CONSTRAINT fk_dialect_phrase_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "dialect_example" ADD CONSTRAINT fk_dialect_example_project FOREIGN KEY (project_id) REFERENCES "project"(id);
ALTER TABLE "dialect_correction" ADD CONSTRAINT fk_dialect_correction_project FOREIGN KEY (project_id) REFERENCES "project"(id);

CREATE INDEX ix_generation_job_poll ON "generation_job"(status,retry_at,created_at);
CREATE INDEX ix_generation_job_shot ON "generation_job"(shot_id,status);
CREATE INDEX ix_keyframe_expiration ON "keyframe"(handoff_status,provider_url_expires_at);
