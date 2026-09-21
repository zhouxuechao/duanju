package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpatialAnchorTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void continuousShotKeepsWorldRelationWhileAllowingScreenProjectionToChange() {
        ArrayNode previous=mapper.createArrayNode().add(anchor("CHAR_1","DOOR","LEFT_OF","FACING_EAST","1.2m","WEST"));
        ArrayNode current=mapper.createArrayNode().add(anchor("CHAR_1","DOOR","LEFT_OF","FACING_EAST","1.2m","SCREEN_RIGHT"));

        var resolved=new SpatialAnchor(mapper).resolve(previous,current,true);

        assertThat(resolved.getFirst().path("relation").asText()).isEqualTo("LEFT_OF");
        assertThat(resolved.getFirst().path("side").asText()).isEqualTo("SCREEN_RIGHT");
    }

    @Test void blocksUnexplainedWorldSideFlip() {
        ArrayNode previous=mapper.createArrayNode().add(anchor("CHAR_1","DOOR","LEFT_OF","FACING_EAST","1.2m","WEST"));
        ArrayNode current=mapper.createArrayNode().add(anchor("CHAR_1","DOOR","RIGHT_OF","FACING_EAST","1.2m","EAST"));
        assertThatThrownBy(()->new SpatialAnchor(mapper).resolve(previous,current,true))
                .hasMessageContaining("SPATIAL_ANCHOR_WORLD_RELATION_CHANGED");
    }

    private ObjectNode anchor(String subject,String object,String relation,String facing,String distance,String side){return mapper.createObjectNode().put("subject",subject).put("anchorObject",object).put("relation",relation).put("facing",facing).put("distance",distance).put("side",side);}
}
