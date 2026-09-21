package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialActivationPlanTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void activatesOnlyCurrentShotEntitiesAndExplainsEveryExclusion() {
        ObjectNode shot = mapper.createObjectNode().put("locationId", "LOC_1");
        shot.putArray("characterIds").add("CHAR_1");
        shot.putArray("propIds").add("PROP_1");
        ArrayNode candidates = mapper.createArrayNode()
                .add(candidate("face-1", "CHARACTER_IDENTITY", "CHAR_1", true))
                .add(candidate("face-future", "CHARACTER_IDENTITY", "CHAR_9", false))
                .add(candidate("location-1", "LOCATION_IDENTITY", "LOC_1", true))
                .add(candidate("prop-future", "PROP_IDENTITY", "PROP_9", false));

        MaterialActivationPlan.Result result = new MaterialActivationPlan(mapper).plan(candidates, shot);

        assertThat(result.activated()).extracting(n -> n.path("id").asText())
                .containsExactly("face-1", "location-1");
        assertThat(result.excluded()).hasSize(2).allSatisfy(n ->
                assertThat(n.path("exclusionReason").asText()).isEqualTo("ENTITY_NOT_VISIBLE_IN_SHOT"));
    }

    @Test void explicitUserReferenceIsActivatedButCannotSilentlyChangeItsAuthority() {
        ObjectNode shot = mapper.createObjectNode();
        shot.putArray("characterIds").add("CHAR_1");
        ArrayNode candidates = mapper.createArrayNode().add(candidate("user-ref", "CHARACTER_IDENTITY", "CHAR_9", false)
                .put("userSpecified", true).put("authority", "USER_SPECIFIED"));

        var activated = new MaterialActivationPlan(mapper).plan(candidates, shot).activated().getFirst();

        assertThat(activated.path("authority").asText()).isEqualTo("USER_SPECIFIED");
        assertThat(activated.path("authoritySource").asText()).isEqualTo("USER_SPECIFIED");
        assertThat(activated.path("authorityPriority").asInt()).isGreaterThan(5_000);
    }

    @Test void sourceAuthorityOrderBeatsAutomaticInferenceRegardlessOfUploadOrder() {
        ObjectNode shot=mapper.createObjectNode();shot.putArray("characterIds").add("CHAR_1");
        ArrayNode candidates=mapper.createArrayNode()
                .add(candidate("auto","CHARACTER_LOOK","CHAR_1",false).put("authoritySource","AUTO_INFERRED"))
                .add(candidate("contract","CHARACTER_LOOK","CHAR_1",false).put("authoritySource","SHOT_CONTRACT"));

        var activated=new MaterialActivationPlan(mapper).plan(candidates,shot).activated();

        assertThat(activated).extracting(n->n.path("id").asText()).containsExactly("contract","auto");
        assertThat(activated.getFirst().path("authorityPriority").asInt()).isGreaterThan(activated.getLast().path("authorityPriority").asInt());
    }

    private ObjectNode candidate(String id, String role, String entityId, boolean required) {
        return mapper.createObjectNode().put("id", id).put("role", role).put("entityId", entityId)
                .put("mediaType", "image_url").put("url", "https://media.example.com/" + id + ".png")
                .put("required", required);
    }
}
