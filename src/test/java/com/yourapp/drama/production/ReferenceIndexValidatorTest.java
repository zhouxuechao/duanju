package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceIndexValidatorTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void assignsStableProviderIndexesAfterActivationAndBudgeting() {
        ArrayNode refs=mapper.createArrayNode().add(ref("face","image_url")).add(ref("motion","video_url")).add(ref("voice","audio_url"));
        var mapping=new ReferenceIndexValidator(mapper).map(refs);
        assertThat(mapping).extracting(n->n.path("providerRef").asText()).containsExactly("@image1","@video1","@audio1");
    }

    @Test void rejectsPromptReferenceThatIsAbsentFromFinalRequest() {
        ArrayNode refs=mapper.createArrayNode().add(ref("face","image_url"));
        assertThatThrownBy(()->new ReferenceIndexValidator(mapper).validatePrompt("Use @image2",refs))
                .hasMessageContaining("REFERENCE_INDEX_NOT_BOUND");
    }

    private ObjectNode ref(String id,String type){return mapper.createObjectNode().put("id",id).put("mediaType",type).put("url","https://media.example.com/"+id);}
}
