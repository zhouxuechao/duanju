package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RewriteBoundary boundary = new RewriteBoundary(mapper);

    @Test
    void protectsPreviousEndAndNextStartContracts() {
        ObjectNode previous = mapper.createObjectNode().put("endState", "伤口已包扎");
        previous.putArray("establishedFacts").add("F1");
        ObjectNode current = mapper.createObjectNode().put("episodeNo", 20).put("startState", "伤口已包扎").put("endState", "拿到钥匙");
        current.putArray("unrevealedSecrets").add("S1"); current.putArray("evidenceIds").add("E1");
        ObjectNode next = mapper.createObjectNode().put("startState", "拿着钥匙");
        ObjectNode contract = boundary.build(20, previous, current, next);

        ObjectNode invalidRewrite = current.deepCopy().put("startState", "毫发无伤").put("endState", "钥匙丢失");

        assertThat(boundary.validate(invalidRewrite, contract)).extracting(ProductionModels.Risk::code)
                .contains("PREVIOUS_END_STATE_BROKEN", "NEXT_START_CONTRACT_BROKEN");
        assertThat(contract.path("protected").path("unrevealedSecrets")).containsExactly(mapper.getNodeFactory().textNode("S1"));
    }
}
