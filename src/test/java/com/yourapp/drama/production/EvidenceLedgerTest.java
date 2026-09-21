package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLedgerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceLedger ledger = new EvidenceLedger();

    @Test
    void detectsFutureKnowledgeUnverifiedFactsAndDestroyedEvidenceReappearing() {
        ArrayNode evidence = mapper.createArrayNode();
        ObjectNode e1 = evidence.addObject().put("evidenceId", "E1").put("type", "DOCUMENT").put("formedAt", 20)
                .put("verified", false).put("tampered", false).put("holder", "CH01");
        e1.putArray("obtainedBy").add("CH01"); e1.putArray("knownBy").add("CH02"); e1.putArray("supportsFacts").add("F1");
        ObjectNode e2 = evidence.addObject().put("evidenceId", "E2").put("type", "OBJECT").put("formedAt", 2)
                .put("verified", true).put("tampered", false).put("destroyedAt", 8).put("presentAt", 10).put("holder", "UNKNOWN");
        e2.putArray("obtainedBy"); e2.putArray("knownBy"); e2.putArray("supportsFacts");
        ObjectNode facts = mapper.createObjectNode(); facts.putObject("F1").put("status", "ESTABLISHED");
        ObjectNode knowledge = mapper.createObjectNode(); knowledge.putObject("CH02").put("E1", 5);
        ObjectNode world = mapper.createObjectNode(); world.putArray("characterIds").add("CH01").add("CH02"); world.putArray("locationIds").add("LOC1");

        assertThat(ledger.validate(evidence, facts, knowledge, world, 10)).extracting(ProductionModels.Risk::code)
                .contains("EVIDENCE_FROM_FUTURE", "KNOWLEDGE_BEFORE_OBTAINED", "UNVERIFIED_EVIDENCE_USED_AS_FACT", "DESTROYED_EVIDENCE_REAPPEARED", "INVALID_EVIDENCE_HOLDER");
    }
}
