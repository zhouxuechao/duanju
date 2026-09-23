CREATE TABLE "adaptation_plan" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 novel_id UUID NOT NULL, analysis_revision INTEGER NOT NULL, version INTEGER NOT NULL, status VARCHAR(20) NOT NULL, adaptation_style VARCHAR(20) NOT NULL,
 CONSTRAINT uq_adaptation_plan_project_id UNIQUE(project_id,id),
 CONSTRAINT uq_adaptation_plan_version UNIQUE(project_id,novel_id,version)
);
CREATE TABLE "episode_adaptation_plan" (
 id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>0), created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
 plan_id UUID NOT NULL, episode_no INTEGER NOT NULL CHECK(episode_no>0), status VARCHAR(20) NOT NULL,
 CONSTRAINT uq_episode_adaptation_plan_project_id UNIQUE(project_id,id),
 CONSTRAINT uq_episode_adaptation_plan_number UNIQUE(project_id,plan_id,episode_no),
 CONSTRAINT ck_episode_adaptation_plan_parent CHECK(parent_id=plan_id)
);
CREATE INDEX ix_adaptation_plan_novel_status ON "adaptation_plan"(project_id,novel_id,status);
CREATE INDEX ix_episode_adaptation_plan_order ON "episode_adaptation_plan"(project_id,plan_id,episode_no);
