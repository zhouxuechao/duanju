package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBeatExtractorTest {
    @Test void preservesAuthoredMeaningAndPresentationForTheDirector(){
        ObjectNode script=JsonNodeFactory.instance.objectNode(),beat=JsonNodeFactory.instance.objectNode()
            .put("beatId","B1").put("purpose","建立观看问题").put("action","人物打开柜门")
            .put("dialogue","").put("visualInformation","柜内坐着另一个自己").put("startSec",0).put("endSec",3);
        beat.putArray("hookSignals").add("IDENTITY_CONTRAST");script.putArray("beatBoundaries").add(beat);

        var extracted=new ScriptBeatExtractor().extract(script).get(0);

        assertThat(extracted.path("action").asText()).isEqualTo("人物打开柜门");
        assertThat(extracted.path("visualInformation").asText()).isEqualTo("柜内坐着另一个自己");
        assertThat(extracted.path("hookSignals")).extracting(value->value.asText()).containsExactly("IDENTITY_CONTRAST");
    }
}
