CREATE TABLE "relationship" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    subject_character_id UUID NOT NULL,
    object_character_id UUID NOT NULL,
    relationship_type TEXT NOT NULL,
    state TEXT NOT NULL,
    valid_from_story_time NUMERIC NOT NULL,
    valid_to_story_time NUMERIC,
    source_scene_id UUID,
    source_shot_id UUID,
    CONSTRAINT uq_relationship_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_relationship_range CHECK (valid_to_story_time IS NULL OR valid_to_story_time > valid_from_story_time)
);
CREATE INDEX ix_relationship_lookup ON "relationship"(project_id,subject_character_id,object_character_id,valid_from_story_time);
