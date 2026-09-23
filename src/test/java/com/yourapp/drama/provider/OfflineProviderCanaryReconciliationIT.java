package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.production.ProviderCanaryReconciler;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled local evidence replay. It has no Provider client or network dependency. */
class OfflineProviderCanaryReconciliationIT {
    @Test
    @EnabledIfEnvironmentVariable(named="RUN_OFFLINE_PROVIDER_CANARY_RECONCILIATION",matches="(?i)true")
    void reconcilesExistingEvidenceWithoutProviderAccess() throws Exception {
        ObjectMapper mapper=new ObjectMapper();Path evidence=Path.of("target","live-canary","provider-canary-evidence.json");
        String expected=System.getenv("OFFLINE_CANARY_EXPECTED_RUN_ID");
        assertThat(mapper.readTree(evidence.toFile()).path("runId").asText()).isEqualTo(expected);
        var result=new ProviderCanaryReconciler(mapper,new ProviderCapabilityRegistry()).reconcile(
                evidence,Path.of("target","live-canary","provider-canary-state.json"),Path.of("target","provider-capability-snapshot.json"),
                Path.of("target","live-canary","provider-canary-reconciled-evidence.json"),Path.of("docs","canary"));
        assertThat(result.state().path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.capability().path("verificationStatus").asText()).isEqualTo("PARTIAL_LIVE_VERIFIED");
    }
}
