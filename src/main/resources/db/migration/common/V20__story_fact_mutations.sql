CREATE TABLE "story_fact_mutation" (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    document TEXT NOT NULL,
    fact_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    effective_from_story_time DECIMAL(14,3) NOT NULL,
    scene_id UUID NOT NULL,
    beat_id UUID NOT NULL,
    CONSTRAINT uq_story_fact_mutation_project_id UNIQUE (project_id,id),
    CONSTRAINT ck_story_fact_mutation_parent CHECK (parent_id=fact_id),
    CONSTRAINT ck_story_fact_mutation_operation CHECK (operation IN ('REVISE','RETRACT','REACTIVATE'))
);
CREATE INDEX ix_story_fact_mutation_project_parent ON "story_fact_mutation"(project_id,parent_id,effective_from_story_time,created_at);
