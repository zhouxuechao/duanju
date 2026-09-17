CREATE TABLE "asset_view" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT fk_asset_view_project FOREIGN KEY (project_id) REFERENCES "project"(id),
    CONSTRAINT uq_asset_view_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_asset_view_project_parent ON "asset_view"(project_id,parent_id,created_at);
