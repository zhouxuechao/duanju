CREATE TABLE "character_definition_version" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    character_id UUID NOT NULL, version INTEGER NOT NULL CHECK (version > 0), supersedes_id UUID,
    effective_from_episode INTEGER, effective_from_scene INTEGER, effective_from_story_time NUMERIC,
    content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_character_definition_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_character_definition_version UNIQUE(project_id,character_id,version),
    CONSTRAINT ck_character_definition_parent CHECK(parent_id=character_id)
);
CREATE INDEX ix_character_definition_timeline ON "character_definition_version"(project_id,character_id,effective_from_episode,effective_from_scene,effective_from_story_time);

CREATE TABLE "location_definition_version" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    location_id UUID NOT NULL, version INTEGER NOT NULL CHECK (version > 0), supersedes_id UUID,
    effective_from_episode INTEGER, effective_from_scene INTEGER, effective_from_story_time NUMERIC,
    content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_location_definition_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_location_definition_version UNIQUE(project_id,location_id,version),
    CONSTRAINT ck_location_definition_parent CHECK(parent_id=location_id)
);
CREATE INDEX ix_location_definition_timeline ON "location_definition_version"(project_id,location_id,effective_from_episode,effective_from_scene,effective_from_story_time);

CREATE TABLE "prop_definition_version" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    prop_id UUID NOT NULL, version INTEGER NOT NULL CHECK (version > 0), supersedes_id UUID,
    effective_from_episode INTEGER, effective_from_scene INTEGER, effective_from_story_time NUMERIC,
    content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_prop_definition_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_prop_definition_version UNIQUE(project_id,prop_id,version),
    CONSTRAINT ck_prop_definition_parent CHECK(parent_id=prop_id)
);
CREATE INDEX ix_prop_definition_timeline ON "prop_definition_version"(project_id,prop_id,effective_from_episode,effective_from_scene,effective_from_story_time);

CREATE TABLE "dependency_edge" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    source_kind VARCHAR(80) NOT NULL, source_id UUID NOT NULL, target_kind VARCHAR(80) NOT NULL,
    target_id UUID NOT NULL, dependency_type VARCHAR(100) NOT NULL,
    episode_no INTEGER, scene_no INTEGER, story_time NUMERIC,
    CONSTRAINT uq_dependency_edge_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_dependency_edge UNIQUE(project_id,source_kind,source_id,target_kind,target_id,dependency_type)
);
CREATE INDEX ix_dependency_edge_source ON "dependency_edge"(project_id,source_kind,source_id,episode_no,scene_no,story_time);

CREATE TABLE "revalidation_marker" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    resource_kind VARCHAR(80) NOT NULL, resource_id UUID NOT NULL, source_version_id UUID,
    status VARCHAR(40) NOT NULL,
    CONSTRAINT uq_revalidation_marker_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_revalidation_marker UNIQUE(project_id,resource_kind,resource_id,source_version_id,status)
);
CREATE INDEX ix_revalidation_marker_resource ON "revalidation_marker"(project_id,resource_kind,resource_id,status);

CREATE TABLE "production_input_snapshot" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    source_kind VARCHAR(80) NOT NULL, source_id UUID, prompt_version_id UUID, script_version_id UUID,
    asset_snapshot_hash VARCHAR(64) NOT NULL, snapshot_key VARCHAR(128) NOT NULL,
    CONSTRAINT uq_production_input_snapshot_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_production_input_snapshot_key UNIQUE(project_id,snapshot_key)
);
CREATE INDEX ix_production_input_snapshot_source ON "production_input_snapshot"(project_id,source_kind,source_id);
