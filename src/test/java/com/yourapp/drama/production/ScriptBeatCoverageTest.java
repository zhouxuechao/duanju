package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBeatCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void blocksMissingRequiredBeatAndOutOfOrderCoverage() {
        ArrayNode beats=mapper.createArrayNode(); beats.addObject().put("beatId","BT01").put("required",true); beats.addObject().put("beatId","BT02").put("required",true); beats.addObject().put("beatId","BT03").put("required",true);
        ArrayNode shots=mapper.createArrayNode(); shots.addObject().putArray("coversBeats").add("BT02"); shots.addObject().putArray("coversBeats").add("BT01");
        assertThat(new ScriptBeatCoverageValidator().validate(beats,shots)).extracting(ProductionModels.Risk::code)
                .contains("REQUIRED_BEAT_MISSING","BEAT_ORDER_CHANGED");
    }
}
