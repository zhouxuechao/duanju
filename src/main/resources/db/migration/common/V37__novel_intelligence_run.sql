CREATE TABLE "novel_intelligence_run" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, type VARCHAR(32) NOT NULL, profile VARCHAR(32) NOT NULL, status VARCHAR(40) NOT NULL, model TEXT,
 CONSTRAINT uq_novel_intelligence_run_project_id UNIQUE(project_id,id)
);
CREATE INDEX ix_novel_intelligence_run_state ON "novel_intelligence_run"(project_id,novel_id,type,status);
