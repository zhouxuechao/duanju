package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class StoryDevelopmentSchemas {
   private StoryDevelopmentSchemas() {
   }

   private static ObjectNode textRule() {
      return Documents.obj().put("type", "string").put("minLength", 1);
   }

   private static ObjectNode object(Object... pairs) {
      ObjectNode s = Documents.obj().put("type", "object").put("additionalProperties", false);
      ObjectNode p = s.putObject("properties");
      ArrayNode r = s.putArray("required");

      for(int i = 0; i < pairs.length; i += 2) {
         p.set((String)pairs[i], (JsonNode)pairs[i + 1]);
         r.add((String)pairs[i]);
      }

      return s;
   }

   private static ObjectNode array(JsonNode item, int min, int max) {
      return (ObjectNode)Documents.obj().put("type", "array").put("minItems", min).put("maxItems", max).set("items", item);
   }

   private static ObjectNode keys() {
      return array(textRule(), 0, 30);
   }

   public static ObjectNode core() {
      ObjectNode identity = object("age", textRule(), "face", textRule(), "hair", textRule(), "body", textRule(), "voiceDialect", textRule());
      ObjectNode look = object("lookKey", textRule(), "name", textRule(), "description", textRule());
      ObjectNode person = object("characterKey", textRule(), "name", textRule(), "description", textRule(), "identityTraits", identity, "looks", array(look, 1, 10));
      ObjectNode place = object("locationKey", textRule(), "name", textRule(), "description", textRule(), "locationBible", object("layout", textRule(), "spatialAnchors", textRule(), "lighting", textRule()));
      ObjectNode prop = object("propKey", textRule(), "name", textRule(), "description", textRule(), "state", textRule(), "propBible", object("appearance", textRule(), "scale", textRule(), "ownership", textRule()));
      return object("title", textRule(), "logline", textRule(), "worldRules", textRule(), "seasonArc", textRule(), "characterArcs", textRule(), "foreshadowingRules", textRule(), "continuityRules", textRule(), "characters", array(person, 1, 30), "locations", array(place, 1, 30), "props", array(prop, 0, 30));
   }

   private static ObjectNode scene() {
      return object("name", textRule(), "description", textRule(), "duration", Documents.obj().put("type", "number").put("minimum", 1).put("maximum", 1800));
   }

   public static ObjectNode batch(int start, int end) {
      ObjectNode card = object("episodeNo", Documents.obj().put("type", "integer").put("minimum", start).put("maximum", end), "title", textRule(), "summary", textRule(), "startState", textRule(), "endState", textRule(), "characterKeys", keys(), "locationKeys", keys(), "propKeys", keys(), "scenePlan", array(scene(), 1, 60));
      return object("episodes", array(card, end - start + 1, end - start + 1));
   }

   public static ObjectNode script() {
      return object("title", textRule(), "summary", textRule(), "script", textRule().put("minLength", 80), "startState", textRule(), "endState", textRule(), "characterKeys", keys(), "locationKeys", keys(), "propKeys", keys(), "scenes", array(scene(), 1, 60));
   }

   public static ObjectNode forDocument(JsonNode doc) {
      ObjectNode var10000;
      switch (Documents.text(doc, "documentType")) {
         case "CORE" -> var10000 = core();
         case "OUTLINE_BATCH" -> var10000 = batch(doc.path("startEpisode").asInt(), doc.path("endEpisode").asInt());
         case "EPISODE_SCRIPT" -> var10000 = script();
         default -> throw new IllegalArgumentException("未知创作阶段");
      }

      return var10000;
   }
}
