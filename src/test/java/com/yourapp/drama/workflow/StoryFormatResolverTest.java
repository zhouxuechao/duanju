package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class StoryFormatResolverTest {
    private final StoryFormatResolver resolver=new StoryFormatResolver();

    @Test void durationNeverInventsAnimeOrComicPresentation(){
        ObjectNode resolved=resolver.resolve(obj().put("targetDuration",75).put("episodeCount",12).put("ratio","9:16"));
        assertThat(resolved.path("narrativeForm").asText()).isEqualTo("SHORT_DRAMA");
        assertThat(resolved.path("presentation").asText()).isEqualTo("LIVE_ACTION");
        assertThat(resolved.path("orientation").asText()).isEqualTo("VERTICAL");
    }

    @Test void anExplicitCustomFormatIsPreservedAsData(){
        ObjectNode project=obj().put("targetDuration",75).put("ratio","16:9");
        project.set("storyFormat",obj().put("formatId","SERIAL_COMIC").put("narrativeForm","SERIES")
            .put("presentation","COMIC").put("orientation","HORIZONTAL"));
        ObjectNode resolved=resolver.resolve(project);
        assertThat(resolved.path("formatId").asText()).isEqualTo("SERIAL_COMIC");
        assertThat(resolved.path("presentation").asText()).isEqualTo("COMIC");
        assertThat(resolved.path("fingerprint").asText()).hasSize(64);
    }
}
