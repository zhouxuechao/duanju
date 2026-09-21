package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class MaterialActivationTest {
 @Test void providerReceivesOnlyMaterialsUsedByTheShot(){var m=new ObjectMapper();var shot=m.createObjectNode().put("locationId","LOC1");shot.putArray("characterIds").add("CH1");shot.putArray("propIds");var candidates=m.createArrayNode();candidates.addObject().put("id","face").put("role","CHARACTER_LOOK").put("entityId","CH1").put("mediaType","image_url").put("url","https://example.test/face.png");candidates.addObject().put("id","unused").put("role","PROP_IDENTITY").put("entityId","P9").put("mediaType","image_url").put("url","https://example.test/p.png");assertThat(new MaterialActivationPlan(m).plan(candidates,shot).activated()).extracting(n->n.path("id").asText()).containsExactly("face");}
}
