CREATE TABLE "episode_script_version" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    episode_id UUID NOT NULL, version INTEGER NOT NULL CHECK (version > 0), supersedes_id UUID,
    source_version_id UUID, status VARCHAR(40) NOT NULL, content_hash VARCHAR(64) NOT NULL,
    source_mode VARCHAR(20) NOT NULL,
    CONSTRAINT uq_episode_script_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_episode_script_version UNIQUE(project_id,episode_id,version),
    CONSTRAINT ck_episode_script_parent CHECK(parent_id=episode_id)
);
CREATE INDEX ix_episode_script_status ON "episode_script_version"(project_id,episode_id,status,version);

CREATE TABLE "production_script_snapshot" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    episode_id UUID NOT NULL, script_version_id UUID NOT NULL, script_hash VARCHAR(64) NOT NULL,
    continuity_snapshot_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_production_script_snapshot_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_production_script_snapshot_version UNIQUE(project_id,episode_id,script_version_id),
    CONSTRAINT ck_production_script_snapshot_parent CHECK(parent_id=episode_id)
);
CREATE INDEX ix_production_script_snapshot_episode ON "production_script_snapshot"(project_id,episode_id,created_at);
