package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves explicit aliases; ambiguous names remain separate instead of being fuzzy-merged. */
@Service
public class EntityResolver {
    private final DocumentStore store;
    public EntityResolver(DocumentStore store) { this.store = store; }
    public ObjectNode resolve(String projectId, String name, double storyTime) {
        ObjectNode result = obj().put("name", name).put("storyTime", storyTime);
        ArrayNode matches = result.putArray("matches");
        Set<String> ids = new LinkedHashSet<>();
        for (ObjectNode character : store.list(CHARACTER, projectId, null)) if (name.equals(text(character,"name"))) ids.add(id(character));
        for (ObjectNode alias : store.list(ENTITY_ALIAS, projectId, null)) {
            if (!name.equals(text(alias,"alias")) || !activeAt(alias, storyTime)) continue;
            ids.add(text(alias,"entityId"));
        }
        for (String entityId : ids) matches.add(obj().put("entityId", entityId).put("ambiguous", false));
        if (matches.size() > 1) matches.forEach(m -> ((ObjectNode)m).put("ambiguous", true));
        return result;
    }
    private boolean activeAt(ObjectNode alias, double time) { return time >= number(alias,"validFromStoryTime") && (!alias.has("validToStoryTime") || alias.path("validToStoryTime").isNull() || time < number(alias,"validToStoryTime")); }
    private double number(ObjectNode node, String field) { return node.path(field).isNumber() ? node.path(field).asDouble() : Double.NEGATIVE_INFINITY; }
}
