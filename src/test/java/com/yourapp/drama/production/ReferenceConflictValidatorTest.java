package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceConflictValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void rejectsTwoEqualAuthorityReferencesControllingSameIdentity() {
        ArrayNode refs=mapper.createArrayNode().add(ref("a","CHAR_1",100)).add(ref("b","CHAR_1",100));
        assertThatThrownBy(()->new ReferenceConflictValidator().validate(refs))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REFERENCE_AUTHORITY_CONFLICT");
    }

    @Test void allowsMultiViewOfSameEntityWhenDeclaredAsOneAuthorityGroup() {
        ArrayNode refs=mapper.createArrayNode().add(ref("front","CHAR_1",100).put("authorityGroup","look-set-1"))
                .add(ref("side","CHAR_1",100).put("authorityGroup","look-set-1"));
        new ReferenceConflictValidator().validate(refs);
    }

    @Test void sequenceBoundaryDoesNotTreatTwoDifferentCharactersAsCompetingIdentityAuthorities() {
        ObjectNode request=mapper.createObjectNode();ObjectNode shot=request.putObject("shot").put("feltIntent","让观众辨认两人的反应").put("endpoint","两人同时停下").put("relationToPrevious","ESTABLISHING");
        shot.putArray("completedBeats");shot.putArray("reservedFutureBeats");
        ArrayNode refs=mapper.createArrayNode().add(ref("a","CHAR_1",100)).add(ref("b","CHAR_2",100));
        java.util.List<JsonNode> bindings=new java.util.ArrayList<>();refs.forEach(bindings::add);
        new SequenceBoundaryGate().validate(request,bindings,java.util.List.of("两人同时停下"));
    }

    private ObjectNode ref(String id,String entity,int priority){ObjectNode n=mapper.createObjectNode().put("id",id).put("entityId",entity).put("authorityPriority",priority).put("role","CHARACTER_IDENTITY");n.putArray("controls").add("characterIdentity");return n;}
}
