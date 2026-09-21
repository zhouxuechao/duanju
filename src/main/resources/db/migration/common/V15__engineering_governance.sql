CREATE TABLE "price_snapshot" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    CONSTRAINT uq_price_snapshot_project_id UNIQUE (project_id,id),
    CONSTRAINT fk_price_snapshot_project FOREIGN KEY (project_id) REFERENCES "project"(id)
);
CREATE INDEX ix_price_snapshot_project_parent ON "price_snapshot"(project_id,parent_id,created_at);

CREATE TABLE "human_edit_feedback" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    CONSTRAINT uq_human_edit_feedback_project_id UNIQUE (project_id,id),
    CONSTRAINT fk_human_edit_feedback_project FOREIGN KEY (project_id) REFERENCES "project"(id)
);
CREATE INDEX ix_human_edit_feedback_project_parent ON "human_edit_feedback"(project_id,parent_id,created_at);

CREATE TABLE "rule_experiment" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    CONSTRAINT uq_rule_experiment_project_id UNIQUE (project_id,id),
    CONSTRAINT fk_rule_experiment_project FOREIGN KEY (project_id) REFERENCES "project"(id)
);
CREATE INDEX ix_rule_experiment_project_parent ON "rule_experiment"(project_id,parent_id,created_at);

CREATE TABLE "pipeline_run" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL,
    CONSTRAINT uq_pipeline_run_project_id UNIQUE (project_id,id),
    CONSTRAINT fk_pipeline_run_project FOREIGN KEY (project_id) REFERENCES "project"(id)
);
CREATE INDEX ix_pipeline_run_project_parent ON "pipeline_run"(project_id,parent_id,created_at);

CREATE TABLE "stage_run" (
    id UUID PRIMARY KEY, project_id UUID NOT NULL, parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL, document TEXT NOT NULL, pipeline_run_id UUID NOT NULL,
    CONSTRAINT uq_stage_run_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_stage_run_parent CHECK (parent_id=pipeline_run_id),
    CONSTRAINT fk_stage_run_project FOREIGN KEY (project_id) REFERENCES "project"(id),
    CONSTRAINT fk_stage_run_pipeline FOREIGN KEY (project_id,pipeline_run_id) REFERENCES "pipeline_run"(project_id,id)
);
CREATE INDEX ix_stage_run_project_parent ON "stage_run"(project_id,parent_id,created_at);
