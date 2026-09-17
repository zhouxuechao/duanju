package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Keeps prompt context bounded to the assets explicitly visible in one shot. */
public class ContextResolver {
    public ObjectNode filterAssets(ObjectNode assets, JsonNode shot) {
        ObjectNode filtered = assets.deepCopy();
        Set<String> characterIds = new LinkedHashSet<>(); shot.path("characterIds").forEach(n -> characterIds.add(n.asText()));
        Set<String> propIds = new LinkedHashSet<>(); shot.path("propIds").forEach(n -> propIds.add(n.asText()));
        String locationId = text(shot,"locationId");
        ArrayNode characters = JsonNodeFactory.instance.arrayNode(); assets.path("characters").forEach(n -> { if (characterIds.contains(text(n,"id"))) characters.add(n); }); filtered.set("characters",characters);
        Set<String> lookIds = new LinkedHashSet<>(); shot.path("startState").path("characters").fields().forEachRemaining(e -> { String look=text(e.getValue(),"lookId"); if(!look.isBlank()) lookIds.add(look); });
        ArrayNode looks = JsonNodeFactory.instance.arrayNode(); assets.path("looks").forEach(n -> { if(lookIds.contains(text(n,"id"))) looks.add(n); }); filtered.set("looks",looks);
        ArrayNode locations = JsonNodeFactory.instance.arrayNode(); assets.path("locations").forEach(n -> { if(locationId.equals(text(n,"id"))) locations.add(n); }); filtered.set("locations",locations);
        ArrayNode props = JsonNodeFactory.instance.arrayNode(); assets.path("props").forEach(n -> { if(propIds.contains(text(n,"id"))) props.add(n); }); filtered.set("props",props);
        return filtered;
    }
}
