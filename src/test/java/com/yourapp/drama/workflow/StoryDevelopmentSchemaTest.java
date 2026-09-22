package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryDevelopmentSchemaTest {
    @Test
    void evidenceFormationTimeIsAnEpisodeNumberRatherThanAnInEpisodeSecondOffset() {
        JsonNode formedAt = StoryDevelopmentSchemas.script()
                .at("/properties/evidenceLedger/items/properties/formedAt");

        assertThat(formedAt.path("type").asText()).isEqualTo("integer");
        assertThat(formedAt.path("minimum").asInt()).isEqualTo(1);
        assertThat(formedAt.path("description").asText()).contains("episodeNo");
    }

    @Test
    void everyAuthoredBeatCarriesStructuredHookMeaningAndObservablePresentation() {
        JsonNode beat=StoryDevelopmentSchemas.script().at("/properties/beatBoundaries/items");

        assertThat(beat.path("required")).extracting(JsonNode::asText)
            .contains("action","dialogue","visualInformation","hookSignals");
        assertThat(beat.at("/properties/hookSignals/items/enum")).extracting(JsonNode::asText)
            .contains("CONFLICT","ANOMALY","DANGER","MYSTERY","SECRET","EMOTION","IDENTITY_CONTRAST","QUESTION","VISUAL_SURPRISE");
    }

    @Test
    void outlineAndScriptUseTheSameStructuredEpisodeEndingContract(){
        JsonNode outlineEnding=StoryDevelopmentSchemas.batch(1,1).at("/properties/episodes/items/properties/episodeEnding");
        JsonNode scriptEnding=StoryDevelopmentSchemas.script().at("/properties/episodeEnding");

        assertThat(outlineEnding).isEqualTo(scriptEnding);
        assertThat(scriptEnding.path("required")).extracting(JsonNode::asText)
            .contains("primaryType","strength","description","unresolvedPressure","nextEpisodeQuestion");
    }
}
