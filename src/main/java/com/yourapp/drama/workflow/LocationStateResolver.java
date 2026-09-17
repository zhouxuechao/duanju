package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves the location condition that was true at the requested story time. */
@Service
public class LocationStateResolver {
    private final DocumentStore store;
    public LocationStateResolver(DocumentStore store) { this.store = store; }
    public ObjectNode resolve(String projectId, String locationId, double storyTime) {
        ObjectNode result = obj().put("locationId", locationId).put("storyTime", storyTime);
        ObjectNode selected = null;
        for (ObjectNode state : store.list(LOCATION_STATE, projectId, locationId)) {
            if (!activeAt(state, storyTime)) continue;
            if (selected == null || number(state,"validFromStoryTime") > number(selected,"validFromStoryTime")) selected = state;
        }
        if (selected != null) {
            for (String field : List.of("id","state","description","validFromStoryTime","validToStoryTime","sourceSceneId","sourceShotId"))
                if (selected.has(field)) result.set(field, selected.get(field).deepCopy());
        }
        return result;
    }
    private boolean activeAt(JsonNode node, double time) { return time >= number(node,"validFromStoryTime") && (!node.has("validToStoryTime") || node.path("validToStoryTime").isNull() || time < number(node,"validToStoryTime")); }
    private double number(JsonNode node, String field) { return node.path(field).isNumber() ? node.path(field).asDouble() : Double.POSITIVE_INFINITY; }
}
