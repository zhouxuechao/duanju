CREATE TABLE "novel_ai_job" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, task_type VARCHAR(40) NOT NULL, status VARCHAR(32) NOT NULL,
 provider_request_id TEXT, model TEXT, context_hash VARCHAR(64) NOT NULL, compiler_version TEXT NOT NULL,
 CONSTRAINT uq_novel_ai_job_project_id UNIQUE(project_id,id)
);
CREATE INDEX ix_novel_ai_job_context ON "novel_ai_job"(project_id,novel_id,task_type,context_hash,status);
