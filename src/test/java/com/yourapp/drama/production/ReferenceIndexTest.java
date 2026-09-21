package com.yourapp.drama.production;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ReferenceIndexTest {
 @Test void mapsSemanticBusinessReferencesOnlyAtProviderBoundary(){var m=new ObjectMapper();var refs=m.createArrayNode();refs.addObject().put("id","CHARACTER_A").put("mediaType","image_url").put("url","https://example.test/a.png");refs.addObject().put("id","MOTION_01").put("mediaType","video_url").put("url","https://example.test/m.mp4");var mapped=new ReferenceIndexValidator(m).map(refs);assertThat(mapped).extracting(n->n.path("providerRef").asText()).containsExactly("@image1","@video1");assertThat(mapped).extracting(n->n.path("id").asText()).containsExactly("CHARACTER_A","MOTION_01");}
}
