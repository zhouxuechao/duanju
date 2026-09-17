package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves presentation order separately from the in-world story clock. */
@Service
public class StoryTimelineResolver {
    private static final Set<String> TYPES = Set.of("NORMAL","FLASHBACK","FLASH_FORWARD","MEMORY","DREAM","HALLUCINATION","PARALLEL_TIMELINE","TIME_SKIP");
    private final DocumentStore store;
    public StoryTimelineResolver(DocumentStore store) { this.store = store; }

    public ObjectNode resolve(String projectId, String episodeId) {
        ObjectNode result = obj().put("episodeId", episodeId);
        ArrayNode items = result.putArray("items");
        List<ObjectNode> shots = new ArrayList<>();
        for (ObjectNode scene : store.list(SCENE, projectId, episodeId))
            shots.addAll(store.list(SHOT, projectId, id(scene)));
        shots.sort(Comparator.comparingInt(this::presentationOrder).thenComparingInt(s -> s.path("shotNo").asInt(Integer.MAX_VALUE)));
        int fallback = 1;
        for (ObjectNode shot : shots) {
            ObjectNode item = obj().put("shotId", id(shot)).put("sceneId", required(shot,"sceneId"));
            item.put("presentationOrder", shot.path("presentationOrder").isIntegralNumber() ? shot.path("presentationOrder").asInt() : fallback++);
            if (shot.path("storyTime").isNumber()) item.put("storyTime", shot.path("storyTime").asDouble());
            String type = shot.path("timelineType").asText("NORMAL").toUpperCase(Locale.ROOT);
            item.put("timelineType", TYPES.contains(type) ? type : "NORMAL");
            items.add(item);
        }
        return result;
    }

    private int presentationOrder(ObjectNode shot) { return shot.path("presentationOrder").isIntegralNumber() ? shot.path("presentationOrder").asInt() : Integer.MAX_VALUE; }
}
