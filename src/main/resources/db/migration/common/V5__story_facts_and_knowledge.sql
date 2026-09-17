CREATE TABLE "story_fact" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    fact_key TEXT NOT NULL,
    predicate TEXT NOT NULL,
    valid_from_story_time NUMERIC NOT NULL,
    valid_to_story_time NUMERIC,
    revealed_at_story_time NUMERIC,
    status VARCHAR(32) NOT NULL,
    source_scene_id UUID,
    source_shot_id UUID,
    CONSTRAINT uq_story_fact_project_id UNIQUE (project_id,id),
    CONSTRAINT uq_story_fact_key UNIQUE (project_id,fact_key),
    CONSTRAINT ck_story_fact_range CHECK (valid_to_story_time IS NULL OR valid_to_story_time > valid_from_story_time)
);
CREATE INDEX ix_story_fact_project_time ON "story_fact"(project_id,valid_from_story_time,valid_to_story_time);

CREATE TABLE "character_knowledge" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    character_id UUID NOT NULL,
    fact_id UUID NOT NULL,
    knowledge_state VARCHAR(16) NOT NULL,
    known_from_story_time NUMERIC NOT NULL,
    known_from_scene_id UUID,
    CONSTRAINT uq_character_knowledge_project_id UNIQUE (project_id,id),
    CONSTRAINT uq_character_knowledge_fact UNIQUE (project_id,character_id,fact_id)
);
CREATE INDEX ix_character_knowledge_lookup ON "character_knowledge"(project_id,character_id,known_from_story_time);
