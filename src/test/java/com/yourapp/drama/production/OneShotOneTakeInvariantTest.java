package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneShotOneTakeInvariantTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void blocksAProviderRequestThatContainsMaterialsFromAnotherShot() {
        ArrayNode refs=mapper.createArrayNode()
                .add(mapper.createObjectNode().put("id","a").put("shotId","SHOT_1"))
                .add(mapper.createObjectNode().put("id","b").put("shotId","SHOT_2"));
        assertThatThrownBy(()->new VideoRequestContractValidator().validateOneShot("SHOT_1",refs))
                .hasMessageContaining("ONE_SHOT_ONE_TAKE_VIOLATION");
    }
}
