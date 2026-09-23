package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit Live Canary contract. Disabled unless a real provider evidence file is supplied. */
class VolcengineCapabilityContractTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_LIVE_PROVIDER_CAPABILITY_CANARY", matches = "true")
    void verifiesLiveCanaryEvidenceAndWritesSnapshot() throws Exception {
        String evidencePath = System.getenv("VOLCENGINE_CAPABILITY_EVIDENCE");
        assertThat(evidencePath).as("VOLCENGINE_CAPABILITY_EVIDENCE").isNotBlank();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode evidence = (ObjectNode) mapper.readTree(Files.readString(Path.of(evidencePath)));
        VideoModelProfile profile = new ProviderCapabilityRegistry().profile(evidence.path("model").asText());
        ObjectNode snapshot = new ProviderCapabilityContract().verify(profile, evidence);
        Path output = Path.of("target", "provider-capability-snapshot.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), snapshot);
        assertThat(snapshot.path("verificationStatus").asText()).isEqualTo("LIVE_VERIFIED");
    }
}
