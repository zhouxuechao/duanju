package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineRepeatedShotTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jCutResolvesTheExactTimelineClipWhenTheSameShotAppearsTwice() {
        ArrayNode items = mapper.createArrayNode();
        items.add(video("clip-a-1", "shot-a", 0));
        items.add(video("clip-b", "shot-b", 3000));
        items.add(video("clip-a-2", "shot-a", 6000));
        var audio = mapper.createObjectNode().put("id", "audio-a-1").put("track", "DIALOGUE")
                .put("shotId", "shot-a").put("linkedVideoTimelineItemId", "clip-a-1")
                .put("startMs", -200).put("durationMs", 600);
        audio.putArray("editOperations").add("J_CUT");
        items.add(audio);
        var risks = new ArrayList<ProductionModels.Risk>();

        EditingEngine.validate(items, risks);

        assertThat(risks).extracting(ProductionModels.Risk::code).doesNotContain("J_CUT_POSITION_INVALID", "EDIT_OPERATION_CLIP_REQUIRED");
    }

    @Test
    void jCutWithoutAConcreteClipAnchorIsRejected() {
        ArrayNode items = mapper.createArrayNode();
        items.add(video("clip-a", "shot-a", 0));
        var audio = mapper.createObjectNode().put("track", "DIALOGUE").put("shotId", "shot-a").put("startMs", -200).put("durationMs", 600);
        audio.putArray("editOperations").add("J_CUT");
        items.add(audio);
        var risks = new ArrayList<ProductionModels.Risk>();

        EditingEngine.validate(items, risks);

        assertThat(risks).extracting(ProductionModels.Risk::code).contains("EDIT_OPERATION_CLIP_REQUIRED");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode video(String id, String shotId, long start) {
        return mapper.createObjectNode().put("id", id).put("track", "VIDEO").put("shotId", shotId).put("startMs", start).put("durationMs", 3000);
    }
}
