CREATE TABLE "story_document" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    CONSTRAINT fk_story_document_project FOREIGN KEY (project_id) REFERENCES "project"(id),
    CONSTRAINT uq_story_document_project_id UNIQUE (project_id,id)
);
CREATE INDEX ix_story_document_project ON "story_document"(project_id,created_at);
