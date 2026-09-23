CREATE TABLE "impact_plan" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    source_version_id UUID NOT NULL, target_version_id UUID NOT NULL,
    impact_type VARCHAR(40) NOT NULL, change_scope VARCHAR(40) NOT NULL,
    CONSTRAINT uq_impact_plan_project_id UNIQUE(project_id,id)
);
CREATE INDEX ix_impact_plan_versions ON "impact_plan"(project_id,source_version_id,target_version_id);

CREATE TABLE "rebuild_plan" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    impact_plan_id UUID NOT NULL, status VARCHAR(30) NOT NULL,
    requires_user_confirmation BOOLEAN NOT NULL,
    CONSTRAINT uq_rebuild_plan_project_id UNIQUE(project_id,id),
    CONSTRAINT uq_rebuild_plan_impact UNIQUE(project_id,impact_plan_id)
);

CREATE TABLE "platform_review_issue" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    episode_id UUID NOT NULL, script_version_id UUID NOT NULL, platform VARCHAR(80) NOT NULL,
    reason_code VARCHAR(80) NOT NULL, status VARCHAR(30) NOT NULL, resolution_version_id UUID,
    CONSTRAINT uq_platform_review_issue_project_id UNIQUE(project_id,id),
    CONSTRAINT ck_platform_review_issue_parent CHECK(parent_id=episode_id)
);
CREATE INDEX ix_platform_review_issue_status ON "platform_review_issue"(project_id,episode_id,status);
