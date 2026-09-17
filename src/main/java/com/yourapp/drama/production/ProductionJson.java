package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import java.util.ArrayList;
import java.util.List;
import static com.yourapp.drama.production.ProductionModels.*;

final class ProductionJson {
    private ProductionJson() {}
    static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    static String required(JsonNode node, String field) {
        String result = text(node, field);
        if (result.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return result;
    }
    static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(v -> { if (!v.asText("").isBlank()) result.add(v.asText()); });
        return List.copyOf(result);
    }
    static JsonNode shotNode(JsonNode request) { return request.has("shot") ? request.path("shot") : request; }
    static Shot shot(JsonNode request, ObjectMapper mapper) {
        JsonNode n = shotNode(request);
        if (!n.isObject()) throw new IllegalArgumentException("shot 必须为结构化对象");
        return new Shot(required(n, "shotId"), text(n, "purpose"), n.path("duration").asDouble(0),
            strings(n.path("characterIds")), text(n, "locationId"), strings(n.path("propIds")),
            text(n, "shotSize"), text(n, "cameraAngle"), text(n, "cameraMovement"), text(n, "action"),
            text(n, "visualFocus"), text(n, "emotion"), strings(n.path("dialogueIds")),
            n.path("startState").isObject() ? n.path("startState") : mapper.createObjectNode(),
            n.path("endState").isObject() ? n.path("endState") : mapper.createObjectNode(),
            parseRequired(ShotRelation.class, required(n, "relationToPrevious")),
            parseRequired(Difficulty.class, required(n, "difficulty")));
    }
    static <E extends Enum<E>> E parseRequired(Class<E> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("shot." + (type == ShotRelation.class ? "relationToPrevious" : "difficulty") + " 不在允许值中：" + java.util.Arrays.toString(type.getEnumConstants())); }
    }
    static JsonNode findAsset(JsonNode assets, String collection, String id) {
        JsonNode list = assets.path(collection);
        if (list.isObject()) return list.path(id);
        if (list.isArray()) for (JsonNode asset : list) {
            if (id.equals(text(asset, "id")) || id.equals(text(asset, "characterId"))
                || id.equals(text(asset, "locationId")) || id.equals(text(asset, "propId"))
                || id.equals(text(asset, "lookId"))) return asset;
        }
        return list.path(id);
    }
    static ObjectNode deepMerge(ObjectMapper mapper, JsonNode base, JsonNode overlay) {
        ObjectNode result = base.isObject() ? (ObjectNode) base.deepCopy() : mapper.createObjectNode();
        if (overlay.isObject()) overlay.fields().forEachRemaining(e -> {
            if (e.getValue().isObject() && result.path(e.getKey()).isObject())
                result.set(e.getKey(), deepMerge(mapper, result.path(e.getKey()), e.getValue()));
            else result.set(e.getKey(), e.getValue().deepCopy());
        });
        return result;
    }
    static boolean hasErrors(List<Risk> risks) { return risks.stream().anyMatch(r -> r.severity().equals("ERROR")); }
    static void error(List<Risk> risks, String code, String path, String message) { risks.add(new Risk(code, "ERROR", path, message)); }
}
