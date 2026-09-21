package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryContractValidatorTest {
    private final StoryContractValidator validator = new StoryContractValidator();

    @Test void unitArcsMustCoverEveryEpisodeWithoutOverlapOrGap() {
        ObjectNode core = obj();
        core.putArray("unitArcs")
                .add(unit("U1", 1, 4))
                .add(unit("U2", 6, 10));
        assertThatThrownBy(() -> validator.validateCore(core, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第 5 集");

        core.putArray("unitArcs").add(unit("U1", 1, 6)).add(unit("U2", 6, 10));
        assertThatThrownBy(() -> validator.validateCore(core, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重叠");
    }

    @Test void longOutlineRequiresMidHookAndARealDelta() {
        ObjectNode format = new EpisodeFormatResolver().resolve(obj().put("targetDuration", 180));
        ObjectNode episode = obj().put("episodeFormatId", "GENERAL_LONG").put("beatMode", "DOUBLE_ROUND");
        episode.putArray("scenePlan").add(obj().put("duration", 180));
        episode.putArray("beats").add(obj().put("beatId", "OPEN").put("purpose", "异常出现"));
        episode.putArray("progressionEvents");

        assertThatThrownBy(() -> validator.validateOutlineEpisode(episode, format))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("中段钩子");
    }

    private ObjectNode unit(String id, int start, int end) {
        return obj().put("unitId", id).put("startEpisode", start).put("endEpisode", end);
    }
}
