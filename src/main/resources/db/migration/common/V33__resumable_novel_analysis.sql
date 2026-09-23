CREATE TABLE "novel_chunk_analysis" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 chunk_id UUID NOT NULL, novel_id UUID NOT NULL, content_hash VARCHAR(64) NOT NULL, analysis_version INTEGER NOT NULL, profile VARCHAR(20) NOT NULL,
 CONSTRAINT uq_novel_chunk_analysis_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_chunk_analysis_version UNIQUE(project_id,chunk_id,analysis_version), CONSTRAINT ck_novel_chunk_analysis_parent CHECK(parent_id=chunk_id)
);
CREATE TABLE "novel_chapter_analysis" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 chapter_id UUID NOT NULL, novel_id UUID NOT NULL, analysis_revision INTEGER NOT NULL, profile VARCHAR(20) NOT NULL,
 CONSTRAINT uq_novel_chapter_analysis_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_chapter_analysis_version UNIQUE(project_id,chapter_id,analysis_revision), CONSTRAINT ck_novel_chapter_analysis_parent CHECK(parent_id=chapter_id)
);
CREATE TABLE "entity_candidate" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, entity_kind VARCHAR(20) NOT NULL, canonical_name TEXT NOT NULL, status VARCHAR(32) NOT NULL, confidence NUMERIC NOT NULL,
 CONSTRAINT uq_entity_candidate_project_id UNIQUE(project_id,id)
);
CREATE TABLE "novel_story_arc" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, start_chapter INTEGER NOT NULL, end_chapter INTEGER NOT NULL, analysis_revision INTEGER NOT NULL,
 CONSTRAINT uq_novel_story_arc_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_story_arc_version UNIQUE(project_id,novel_id,start_chapter,analysis_revision), CONSTRAINT ck_novel_story_arc_parent CHECK(parent_id=novel_id)
);
CREATE TABLE "novel_story_graph" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, analysis_revision INTEGER NOT NULL, profile VARCHAR(20) NOT NULL,
 CONSTRAINT uq_novel_story_graph_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_story_graph_version UNIQUE(project_id,novel_id,analysis_revision), CONSTRAINT ck_novel_story_graph_parent CHECK(parent_id=novel_id)
);
CREATE TABLE "novel_analysis_job" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 chunk_id UUID NOT NULL, novel_id UUID NOT NULL, chapter_id UUID NOT NULL, status VARCHAR(20) NOT NULL, idempotency_key TEXT NOT NULL, content_hash VARCHAR(64) NOT NULL, profile VARCHAR(20) NOT NULL, analysis_version INTEGER NOT NULL,
 CONSTRAINT uq_novel_analysis_job_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_analysis_job_key UNIQUE(project_id,idempotency_key), CONSTRAINT ck_novel_analysis_job_parent CHECK(parent_id=chunk_id)
);
CREATE INDEX ix_novel_analysis_job_resume ON "novel_analysis_job"(project_id,novel_id,status);
CREATE INDEX ix_entity_candidate_review ON "entity_candidate"(project_id,novel_id,status);
