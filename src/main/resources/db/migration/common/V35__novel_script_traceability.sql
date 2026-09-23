ALTER TABLE "episode_script_version" ADD COLUMN adaptation_plan_id UUID;
ALTER TABLE "episode_script_version" ADD COLUMN episode_adaptation_plan_id UUID;
ALTER TABLE "episode_script_version" ADD COLUMN context_hash VARCHAR(64);
CREATE INDEX ix_episode_script_adaptation ON "episode_script_version"(project_id,adaptation_plan_id,episode_adaptation_plan_id);
