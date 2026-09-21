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
}
