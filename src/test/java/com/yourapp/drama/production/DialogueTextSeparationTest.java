package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueTextSeparationTest {
    private ObjectMapper mapper;private ProductionService service;
    @BeforeEach void setup(){mapper=new ObjectMapper();var continuity=new ContinuityEngine(mapper);service=new ProductionService(mapper,continuity,new PromptCompiler(mapper,continuity),new ProductionStateMachine(mapper));}

    @Test void dialectConversionNeverChangesSemanticOrSubtitleText(){
        ObjectNode request=mapper.createObjectNode().put("semanticText","你今天去哪里了？")
            .put("subtitleText","你今天去哪里了？").put("dialect","LEIYANG").put("dialectStrength",1);
        request.set("correction",mapper.createObjectNode().put("approved",true).put("spokenText","谐音版耒阳话").put("entryId","e1"));

        var result=service.renderDialect(request);

        assertThat(result.path("semanticText").asText()).isEqualTo("你今天去哪里了？");
        assertThat(result.path("subtitleText").asText()).isEqualTo("你今天去哪里了？");
        assertThat(result.path("spokenText").asText()).isEqualTo("谐音版耒阳话");
    }

    @Test void subtitleRendererConsumesSubtitleTextOnly(){
        ObjectNode request=mapper.createObjectNode(),line=mapper.createObjectNode().put("dialogueId","d1")
            .put("semanticText","标准语义").put("spokenText","方言发音").put("subtitleText","观众字幕")
            .put("startMs",0).put("audioDurationMs",1200);
        request.putArray("dialogues").add(line);

        assertThat(service.subtitle(request).path("srt").asText()).contains("观众字幕").doesNotContain("标准语义","方言发音");
    }
}
