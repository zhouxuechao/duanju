package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Defines the story dimensions that used to be ambiguously compressed into genre. */
@Component
public class StoryProfilePolicy {
    public ObjectNode enrich(ObjectNode project) {
        ObjectNode result = project.deepCopy();
        ObjectNode source = result.path("storyProfile").isObject()
                ? (ObjectNode) result.path("storyProfile").deepCopy() : Documents.obj();
        ObjectNode profile = Documents.obj()
                .put("settingGenre", code(source.path("settingGenre").asText("OTHER"), "OTHER"))
                .put("storyType", code(source.path("storyType").asText("GROWTH"), "GROWTH"))
                .put("audience", code(source.path("audience").asText("GENERAL"), "GENERAL"))
                .put("intensity", code(source.path("intensity").asText(source.path("toneIntensity").asText("MEDIUM")), "MEDIUM"))
                .put("sourceMode", code(source.path("sourceMode").asText("ORIGINAL_IDEA"), "ORIGINAL_IDEA"));
        profile.set("tropes", normalizedArray(source.path("tropes"), true));
        profile.set("tones", normalizedArray(source.path("tones"), false));

        String distribution = code(result.path("distributionProfile").asText(
                source.path("distributionProfile").asText("GENERAL")), "GENERAL");
        result.put("distributionProfile", distribution);
        result.set("storyProfile", profile);
        result.remove("genre");
        result.put("storyProfileFingerprint", fingerprint(profile, distribution));
        return result;
    }

    private ArrayNode normalizedArray(JsonNode value, boolean codes) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (value.isArray()) value.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank() && result.size() < 12) result.add(codes ? code(text, "OTHER") : text);
        });
        return result;
    }

    private String code(String value, String fallback) {
        return value == null || value.isBlank() ? fallback
                : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String fingerprint(JsonNode profile, String distribution) {
        try {
            String value = profile.toString() + '|' + distribution;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
