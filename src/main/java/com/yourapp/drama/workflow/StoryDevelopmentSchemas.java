package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compact structured-output contracts for the three persisted authoring stages. */
public final class StoryDevelopmentSchemas {
    private StoryDevelopmentSchemas() {}

    private static ObjectNode text() { return Documents.obj().put("type", "string").put("minLength", 1); }
    private static ObjectNode bool() { return Documents.obj().put("type", "boolean"); }
    private static ObjectNode number(double min, double max) { return Documents.obj().put("type", "number").put("minimum", min).put("maximum", max); }
    private static ObjectNode integer(int min, int max) { return Documents.obj().put("type", "integer").put("minimum", min).put("maximum", max); }
    private static ObjectNode array(JsonNode item, int min, int max) { return (ObjectNode) Documents.obj().put("type", "array").put("minItems", min).put("maxItems", max).set("items", item); }
    private static ObjectNode strings(int min, int max) { return array(text(), min, max); }

    private static ObjectNode object(Object... pairs) {
        Map<String, JsonNode> required = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) required.put((String)pairs[i], (JsonNode)pairs[i + 1]);
        return object(required, Map.of());
    }

    private static ObjectNode object(Map<String, JsonNode> required, Map<String, JsonNode> optional) {
        ObjectNode schema = Documents.obj().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode requiredNames = schema.putArray("required");
        required.forEach((name, rule) -> { properties.set(name, rule); requiredNames.add(name); });
        optional.forEach(properties::set);
        return schema;
    }

    private static ObjectNode storyProfile() {
        return object("settingGenre", text(), "storyType", text(), "tropes", strings(0, 12), "audience", text(),
                "tones", strings(0, 8), "intensity", text(), "sourceMode", text());
    }

    public static ObjectNode premise() {
        ObjectNode potential = object("conflictDepth", text(), "characterDepth", text(), "relationshipDepth", text(),
                "reversalPotential", text(), "informationDepth", text());
        return object("viable", bool(), "coreConflict", text(), "protagonistGoal", text(), "opposition", text(),
                "audiencePromise", text(), "whyNotResolveImmediately", text(), "expansionPotential", potential,
                "risks", strings(0, 12), "questionsForCore", strings(1, 12));
    }

    private static ObjectNode unitArc() {
        ObjectNode transformation = object("protagonist", text(), "relationships", text(), "mainConflict", text(),
                "audienceKnowledge", text(), "nextStageReason", text());
        return object("unitId", text(), "startEpisode", integer(1, 1000), "endEpisode", integer(1, 1000),
                "title", text(), "goal", text(), "mainConflict", text(), "antagonistPressure", text(),
                "emotionGoal", text(), "reveal", text(), "payoff", text(), "climax", text(), "endHook", text(),
                "unitTransformation", transformation);
    }

    public static ObjectNode core() {
        ObjectNode emotion = object("corePromise", text(), "primaryEmotion", text(), "secondaryEmotion", text(),
                "audienceExpectation", text(), "payoffPattern", text(), "forbiddenPatterns", strings(1, 12));
        ObjectNode engine = object("coreConflict", text(), "protagonistGoal", text(), "oppositionGoal", text(),
                "stakes", text(), "mainPayoff", text(), "escalationAxes", strings(2, 10), "reversalStrategy", text());
        ObjectNode season = object("opening", text(), "development", text(), "majorTurn", text(), "climax", text(), "ending", text());
        ObjectNode arc = object("start", text(), "turningPoints", strings(1, 8), "end", text());
        ObjectNode narrative = object("storyRole", text(), "want", text(), "need", text(), "fear", text(), "weakness", text(),
                "secret", text(), "motivation", text(), "decisionPattern", text(), "arc", arc, "speechStyle", text(),
                "behaviorRules", strings(1, 10), "relationships", strings(0, 12));
        ObjectNode identity = object("age", text(), "face", text(), "hair", text(), "body", text(), "voiceDialect", text());
        ObjectNode look = object("lookKey", text(), "name", text(), "description", text());
        ObjectNode person = object("characterKey", text(), "name", text(), "description", text(), "narrativeBible", narrative,
                "identityTraits", identity, "looks", array(look, 1, 10));
        ObjectNode place = object("locationKey", text(), "name", text(), "description", text(),
                "locationBible", object("layout", text(), "spatialAnchors", text(), "lighting", text()));
        ObjectNode prop = object("propKey", text(), "name", text(), "description", text(), "state", text(),
                "propBible", object("appearance", text(), "scale", text(), "ownership", text()));
        return object("title", text(), "logline", text(), "storyProfile", storyProfile(), "emotionContract", emotion,
                "storyEngine", engine, "worldRules", strings(1, 12), "seasonArc", season,
                "unitArcs", array(unitArc(), 1, 30), "characters", array(person, 1, 30),
                "locations", array(place, 1, 30), "props", array(prop, 0, 30),
                "foreshadowingRules", strings(0, 20), "continuityRules", strings(1, 20));
    }

    private static ObjectNode scene() {
        return object("name", text(), "description", text(), "startSec", number(0, 1800),
                "endSec", number(0, 1800), "duration", number(1, 1800));
    }

    private static ObjectNode beat() {
        return object("beatId", text(), "purpose", text(), "startSec", number(0, 1800), "endSec", number(0, 1800));
    }

    private static ObjectNode midHook() {
        return object("required", bool(), "preferredPositionRatio", number(0.25, 0.75), "type", text(),
                "description", text(), "raisesWhat", text(), "mustNotResolveMainPayoff", bool());
    }

    private static ObjectNode progression() {
        return object("atSec", number(0, 1800), "type", text(), "description", text());
    }

    public static ObjectNode batch(int start, int end) {
        Map<String, JsonNode> required = new LinkedHashMap<>();
        required.put("episodeNo", integer(start, end)); required.put("title", text());
        for (String field : new String[]{"episodeFunction","episodeGoal","hook","mainConflict","newInformation","characterDecision","escalation","payoff","cliffhanger","summary","startState","endState"}) required.put(field, text());
        required.put("characterKeys", strings(0, 30)); required.put("locationKeys", strings(0, 30)); required.put("propKeys", strings(0, 30));
        required.put("foreshadowing", object("plant", strings(0, 10), "advance", strings(0, 10), "resolve", strings(0, 10)));
        required.put("episodeFormatId", text()); required.put("beatMode", text()); required.put("beats", array(beat(), 2, 24));
        required.put("progressionEvents", array(progression(), 1, 24)); required.put("estimatedDurationSec", number(1, 1800));
        required.put("scenePlan", array(scene(), 1, 20));
        ObjectNode card = object(required, Map.of("midHook", midHook()));
        return object("episodes", array(card, end - start + 1, end - start + 1));
    }

    public static ObjectNode script() {
        Map<String, JsonNode> required = new LinkedHashMap<>();
        for (String field : new String[]{"title","summary","startState","endState","episodeFormatId","beatMode"}) required.put(field, text());
        required.put("script", text().put("minLength", 80)); required.put("targetDurationSec", number(1, 1800));
        required.put("characterKeys", strings(0, 30)); required.put("locationKeys", strings(0, 30)); required.put("propKeys", strings(0, 30));
        required.put("beatBoundaries", array(beat(), 2, 24)); required.put("scenes", array(scene(), 1, 20));
        return object(required, Map.of("midHook", midHook()));
    }

    public static ObjectNode forDocument(JsonNode doc) {
        return switch (Documents.text(doc, "documentType")) {
            case "CORE" -> core();
            case "OUTLINE_BATCH" -> batch(doc.path("startEpisode").asInt(), doc.path("endEpisode").asInt());
            case "EPISODE_SCRIPT" -> script();
            default -> throw new IllegalArgumentException("未知创作阶段");
        };
    }
}
