CREATE TABLE "beat" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    scene_id UUID NOT NULL,
    CONSTRAINT uq_beat_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_beat_parent CHECK (parent_id=scene_id)
);
CREATE INDEX ix_beat_project_parent ON "beat"(project_id,parent_id,created_at);
