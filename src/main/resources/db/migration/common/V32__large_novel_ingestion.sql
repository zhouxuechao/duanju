CREATE TABLE "novel_upload_session" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 upload_id UUID NOT NULL, status VARCHAR(30) NOT NULL, file_sha256 VARCHAR(64) NOT NULL, storage_key VARCHAR(260) NOT NULL,
 CONSTRAINT uq_novel_upload_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_upload_id UNIQUE(upload_id)
);
CREATE TABLE "novel_source" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 format VARCHAR(20) NOT NULL, file_hash VARCHAR(64) NOT NULL, status VARCHAR(30) NOT NULL, storage_key VARCHAR(260) NOT NULL,
 CONSTRAINT uq_novel_source_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_source_hash UNIQUE(project_id,file_hash)
);
CREATE TABLE "novel_chapter" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, chapter_no INTEGER NOT NULL, content_hash VARCHAR(64) NOT NULL, source_start INTEGER NOT NULL, source_end INTEGER NOT NULL,
 CONSTRAINT uq_novel_chapter_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_chapter_no UNIQUE(project_id,novel_id,chapter_no), CONSTRAINT ck_novel_chapter_parent CHECK(parent_id=novel_id)
);
CREATE TABLE "novel_chunk" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 chapter_id UUID NOT NULL, novel_id UUID NOT NULL, chunk_no INTEGER NOT NULL, content_hash VARCHAR(64) NOT NULL, source_start INTEGER NOT NULL, source_end INTEGER NOT NULL,
 CONSTRAINT uq_novel_chunk_project_id UNIQUE(project_id,id), CONSTRAINT uq_novel_chunk_no UNIQUE(project_id,chapter_id,chunk_no), CONSTRAINT ck_novel_chunk_parent CHECK(parent_id=chapter_id)
);
CREATE TABLE "source_reference" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 chunk_id UUID NOT NULL, novel_id UUID NOT NULL, source_type VARCHAR(20) NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, content_hash VARCHAR(64) NOT NULL,
 CONSTRAINT uq_source_reference_project_id UNIQUE(project_id,id), CONSTRAINT ck_source_reference_parent CHECK(parent_id=chunk_id)
);
CREATE INDEX ix_novel_chapter_source ON "novel_chapter"(project_id,novel_id,source_start,source_end);
CREATE INDEX ix_novel_chunk_source ON "novel_chunk"(project_id,novel_id,source_start,source_end);
