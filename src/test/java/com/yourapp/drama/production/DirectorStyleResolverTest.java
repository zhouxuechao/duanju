package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DirectorStyleResolverTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void sceneOverrideMergesIntoProjectProfileWithoutGenreBranches(){
        ObjectNode project=mapper.createObjectNode();project.putObject("directorStyleProfile").put("visualRhythm","克制").put("averageShotLength",3.2)
            .put("cameraActivity","LOW").put("closeUpPreference",0.4).put("reactionShotPreference",0.6).put("compositionStyle","纵深").put("tensionStyle","信息递进");
        ObjectNode scene=mapper.createObjectNode();scene.putObject("directorStyleOverride").put("averageShotLength",2.4).put("reactionShotPreference",0.9);
        ObjectNode result=new DirectorStyleResolver(mapper).resolve(project,scene);
        assertThat(result.path("visualRhythm").asText()).isEqualTo("克制");assertThat(result.path("averageShotLength").asDouble()).isEqualTo(2.4);
        assertThat(result.path("cameraActivity").asText()).isEqualTo("LOW");assertThat(result.path("reactionShotPreference").asDouble()).isEqualTo(0.9);
    }
}
