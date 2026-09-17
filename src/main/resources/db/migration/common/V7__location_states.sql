CREATE TABLE "location_state" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    location_id UUID NOT NULL,
    state TEXT,
    valid_from_story_time NUMERIC,
    valid_to_story_time NUMERIC,
    source_scene_id UUID,
    source_shot_id UUID,
    CONSTRAINT uq_location_state_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_location_state_parent CHECK (parent_id=location_id),
    CONSTRAINT ck_location_state_range CHECK (valid_from_story_time IS NULL OR valid_to_story_time IS NULL OR valid_to_story_time > valid_from_story_time)
);
CREATE INDEX ix_location_state_project_parent ON "location_state"(project_id,parent_id,created_at);
