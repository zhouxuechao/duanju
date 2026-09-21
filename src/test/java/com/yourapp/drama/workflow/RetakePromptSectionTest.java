package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetakePromptSectionTest {
    @Test void eachFailureCategoryChangesOnlyItsResponsiblePromptSection(){
        assertThat(WorkflowService.retakeSection("IDENTITY_MISMATCH")).isEqualTo("HIGH-RISK CONTINUITY LOCKS");
        assertThat(WorkflowService.retakeSection("PROP_MISMATCH")).isEqualTo("PHYSICS / INTERACTION");
        assertThat(WorkflowService.retakeSection("ACTION_MISMATCH")).isEqualTo("TIMED BEATS");
        assertThat(WorkflowService.retakeSection("CAMERA_MISMATCH")).isEqualTo("CAMERA / MOTION PHASE");
        assertThat(WorkflowService.retakeSection("EXPRESSION_MISMATCH")).isEqualTo("SHOT INTENT / CARRIERS");
        assertThat(WorkflowService.retakeSection("CONTINUITY_MISMATCH")).isEqualTo("ACTUAL OPENING STATE");
        assertThat(WorkflowService.retakeSection("REFERENCE_FAILURE")).isEqualTo("REFERENCE AUTHORITY");
        assertThat(WorkflowService.retakeSection("TEXT_OR_WATERMARK")).isEqualTo("OUTPUT CONSTRAINTS");
    }
}
