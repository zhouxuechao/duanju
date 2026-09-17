CREATE TABLE "entity_alias" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    entity_id UUID NOT NULL,
    alias TEXT NOT NULL,
    alias_type TEXT NOT NULL,
    valid_from_story_time NUMERIC,
    valid_to_story_time NUMERIC,
    source TEXT,
    confidence NUMERIC,
    CONSTRAINT uq_entity_alias_project_id UNIQUE (project_id,id),
    CONSTRAINT uq_entity_alias_value UNIQUE (project_id,alias,entity_id)
);
CREATE INDEX ix_entity_alias_lookup ON "entity_alias"(project_id,alias);
