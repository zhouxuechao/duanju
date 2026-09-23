package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuitySingleSourceContractTest {
    @Test
    void servicesDoNotImplementTheirOwnStateCompatibilityRules() throws Exception {
        String timeline = source("src/main/java/com/yourapp/drama/workflow/TimelineQualityService.java");
        String workflow = source("src/main/java/com/yourapp/drama/workflow/WorkflowService.java");
        String boundary = source("src/main/java/com/yourapp/drama/production/ProductionBoundaryDeterministicRule.java");

        assertThat(timeline).contains("ContinuityCompatibilityEvaluator").doesNotContain("boolean compatible(");
        assertThat(workflow).contains("ContinuityCompatibilityEvaluator").doesNotContain("new CrossShotQc");
        assertThat(boundary).contains("ContinuityCompatibilityEvaluator").doesNotContain("new CrossShotQc");
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
