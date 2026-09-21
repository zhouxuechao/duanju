package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceBudgeterTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void trimsLowestPriorityOptionalReferencesAndNeverDropsRequiredOnes() {
        ArrayNode refs=mapper.createArrayNode();
        refs.add(ref("identity",100,true));
        refs.add(ref("location",94,true));
        refs.add(ref("composition",82,false));
        refs.add(ref("motion",78,false));
        refs.add(ref("style",20,false));

        ReferenceBudgeter.Result result=new ReferenceBudgeter().fit(refs,new VideoModelProfile.ReferenceLimits(4,1,1,4));

        assertThat(result.selected()).extracting(n->n.path("id").asText()).contains("identity","location").doesNotContain("style");
        assertThat(result.excluded()).extracting(n->n.path("budgetReason").asText()).containsOnly("RECOMMENDED_REFERENCE_BUDGET");
    }
    private ObjectNode ref(String id,int priority,boolean required){return mapper.createObjectNode().put("id",id).put("mediaType","image_url").put("authorityPriority",priority).put("required",required);}
}
