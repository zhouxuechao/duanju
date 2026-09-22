package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SatisfactionEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SatisfactionEngine engine = new SatisfactionEngine();

    @Test
    void everyExplicitContractIsValidatedWithoutAStoryTypeAllowlist() {
        ObjectNode contract = mapper.createObjectNode().put("negativeEmotion", "HUMILIATION")
                .put("informationGap", "").put("payoff", "PUBLIC_REVERSAL").put("payoffDelayEpisodes", 4);
        contract.putArray("amplifiers").add("PUBLIC_OCCASION");
        ArrayNode episodes = mapper.createArrayNode();
        episodes.addObject().put("pressureIncreased", true).put("payoffAdvanced", true).put("payoff", "PARTIAL_REVERSAL");

        SatisfactionEngine.Result result = engine.evaluate(contract, episodes);

        assertThat(result.applicable()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.risks()).extracting(ProductionModels.Risk::code).contains("INFORMATION_GAP_MISSING");
    }

    @Test
    void flagsMechanicalPayoffRepetitionAcrossRecentEpisodes() {
        ObjectNode contract = mapper.createObjectNode().put("negativeEmotion", "LOSS").put("informationGap", "HIDDEN_IDENTITY")
                .put("payoff", "PUBLIC_REVERSAL").put("payoffDelayEpisodes", 3);
        contract.putArray("amplifiers").add("PUBLIC_OCCASION");
        ArrayNode episodes = mapper.createArrayNode();
        for (int i = 0; i < 4; i++) episodes.addObject().put("pressureIncreased", true).put("payoffAdvanced", true).put("payoff", "PUBLIC_REVERSAL");

        assertThat(engine.evaluate(contract, episodes).risks())
                .extracting(ProductionModels.Risk::code).contains("MECHANICAL_PAYOFF_REPETITION");
    }
}
