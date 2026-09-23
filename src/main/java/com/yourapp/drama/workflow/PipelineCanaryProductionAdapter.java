package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/** Maps the canary harness onto the existing production workflow. */
@FunctionalInterface
public interface PipelineCanaryProductionAdapter {
    enum Capability {
        PROJECT_PERSISTENCE,
        EPISODE_SCENE,
        STORY,
        BEAT_SHOT,
        CONTINUITY,
        PROMPT_IR,
        KEYFRAME_WORKFLOW,
        KEYFRAME_QC,
        VIDEO_TAKE,
        VIDEO_QC,
        SELECTED_TAKE,
        DIALOGUE_THREE_TRACK,
        TTS,
        TIMELINE_CLIP,
        RENDER,
        FINAL_QA,
        DOMAIN_RECONCILIATION
    }

    Set<Capability> capabilities();

    default ObjectNode bindings() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    default ObjectNode start(PipelineCanaryFixture fixture, String runId, String mode) {
        throw new UnsupportedOperationException("Production start is not implemented");
    }

    default ObjectNode resume(String pipelineRunId) {
        throw new UnsupportedOperationException("Production resume is not implemented");
    }

    default ObjectNode inspect(String projectId, String pipelineRunId) {
        throw new UnsupportedOperationException("Production inspection is not implemented");
    }

    default ObjectNode reconcile(ObjectNode cursor) {
        throw new UnsupportedOperationException("Production reconciliation is not implemented");
    }
}
