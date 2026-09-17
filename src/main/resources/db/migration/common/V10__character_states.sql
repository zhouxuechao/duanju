CREATE TABLE "character_state" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL, character_id UUID NOT NULL, state TEXT, valid_from_story_time NUMERIC, valid_to_story_time NUMERIC, source_scene_id UUID, source_shot_id UUID,
 CONSTRAINT uq_character_state_project_id UNIQUE(project_id,id), CONSTRAINT ck_character_state_parent CHECK(parent_id=character_id), CONSTRAINT ck_character_state_range CHECK(valid_from_story_time IS NULL OR valid_to_story_time IS NULL OR valid_to_story_time>valid_from_story_time)
);
CREATE INDEX ix_character_state_lookup ON "character_state"(project_id,character_id,valid_from_story_time);
