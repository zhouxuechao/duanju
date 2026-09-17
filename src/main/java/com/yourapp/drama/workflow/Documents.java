package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;

public final class Documents {
    private Documents() { }
    public static ObjectNode obj() { return JsonNodeFactory.instance.objectNode(); }
    public static String required(JsonNode node,String key) {
        if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new IllegalArgumentException("缺少字段："+key);
        return node.path(key).asText();
    }
    public static String text(JsonNode node,String key) { return node.path(key).asText(""); }
    public static long revision(JsonNode node) { return node.path("revision").asLong(); }
    public static String id(JsonNode node) { return required(node,"id"); }
    public static String project(JsonNode node) { return required(node,"projectId"); }
    public static Instant createdAt(JsonNode node) {
        String value=text(node,"createdAt");return value.isBlank()?Instant.MIN:Instant.parse(value);
    }
    public static ObjectNode laterReview(ObjectNode current,ObjectNode next) {
        long currentSequence=current.path("reviewSequence").asLong(0),nextSequence=next.path("reviewSequence").asLong(0);
        if(currentSequence!=nextSequence)return nextSequence>currentSequence?next:current;
        return !createdAt(next).isBefore(createdAt(current))?next:current;
    }
    public static boolean expired(JsonNode node) {
        String value=text(node,"providerUrlExpiresAt");
        return !value.isBlank() && !Instant.parse(value).isAfter(Instant.now().plusSeconds(10));
    }
    public static ObjectNode merge(ObjectNode original, JsonNode patch) {
        ObjectNode result=original.deepCopy(); patch.fields().forEachRemaining(e -> result.set(e.getKey(),e.getValue())); return result;
    }
}
