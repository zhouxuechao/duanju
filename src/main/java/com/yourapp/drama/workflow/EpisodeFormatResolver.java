package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Resolves story length policy independently from genre and provider prompts. */
@Component
public class EpisodeFormatResolver {
    private static final Set<String> FAMILIES = Set.of("MICRO", "MANJU", "STANDARD", "LONG", "CUSTOM");

    public ObjectNode resolve(JsonNode project) {
        JsonNode explicit = project.path("episodeFormat");
        String distribution = normalized(project.path("distributionProfile").asText(
                project.path("storyProfile").path("distributionProfile").asText("GENERAL")), "GENERAL");
        if (explicit.isObject() && explicit.hasNonNull("profileId")) {
            if (explicit.path("profileId").asText().startsWith("HONGGUO_")) distribution="HONGGUO";
            String family = normalized(explicit.path("family").asText("CUSTOM"), "CUSTOM");
            if (!FAMILIES.contains(family)) throw new IllegalArgumentException("episodeFormat.family 无效：" + family);
            int seconds = positive(explicit.path("targetDurationSec").asInt(
                    project.path("targetDuration").asInt(20)), "episodeFormat.targetDurationSec");
            ObjectNode result = defaults(family, seconds, distribution);
            explicit.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue().deepCopy()));
            result.put("family", family).put("targetDurationSec", seconds).put("distributionProfile", distribution);
            validate(result);
            return fingerprint(result);
        }

        int seconds = positive(project.path("targetDuration").asInt(20), "targetDuration");
        String family = inferFamily(seconds);
        return fingerprint(defaults(family, seconds, distribution).put("profileId", profileId(family, seconds, distribution)));
    }

    public int recommendedEpisodesPerUnit(JsonNode format) {
        return format.path("unitDensityPolicy").path("recommendedEpisodesPerUnit").asInt(5);
    }

    private ObjectNode defaults(String family, int seconds, String distribution) {
        int beats;
        int scenes;
        int interval;
        int episodesPerUnit;
        String beatMode;
        String midHook;
        switch (family) {
            case "MICRO" -> { beats = 3; scenes = 2; interval = Math.max(8, seconds / 2); episodesPerUnit = 8; beatMode = "SINGLE_ROUND"; midHook = "NONE"; }
            case "MANJU" -> { beats = 5; scenes = 4; interval = 20; episodesPerUnit = 6; beatMode = "SINGLE_ROUND"; midHook = "OPTIONAL"; }
            case "STANDARD" -> { beats = 7; scenes = 6; interval = 25; episodesPerUnit = 5; beatMode = "SINGLE_ROUND"; midHook = "OPTIONAL"; }
            case "LONG" -> { beats = 10; scenes = 9; interval = 30; episodesPerUnit = 3; beatMode = "DOUBLE_ROUND"; midHook = "REQUIRED_AT_HALF"; }
            default -> { beats = Math.max(3, (int)Math.ceil(seconds / 18.0)); scenes = Math.max(2, (int)Math.ceil(seconds / 30.0)); interval = Math.max(15, Math.min(30, seconds / 4)); episodesPerUnit = seconds >= 150 ? 3 : 5; beatMode = seconds >= 150 ? "DOUBLE_ROUND" : "CUSTOM"; midHook = seconds >= 150 ? "REQUIRED_AT_HALF" : "OPTIONAL"; }
        }
        ObjectNode range = Documents.obj().put("min", rangeMin(family, seconds)).put("max", rangeMax(family, seconds));
        ObjectNode density = Documents.obj().put("recommendedEpisodesPerUnit", episodesPerUnit)
                .put("maxEpisodesPerUnit", episodesPerUnit + 2);
        boolean hongguo=distribution.startsWith("HONGGUO");
        if(hongguo){if("MANJU".equals(family)||"STANDARD".equals(family))scenes=2;else if("LONG".equals(family))scenes=3;}
        ObjectNode value = Documents.obj().put("profileId", profileId(family, seconds, distribution)).put("family", family)
                .put("targetDurationSec", seconds).put("beatMode", beatMode).put("targetBeatCount", beats)
                .put("sceneLimit", scenes).put("midHookPolicy", midHook).put("progressionIntervalSec", interval)
                .put("storyQaProfile", "FORMAT_" + family).put("distributionProfile", distribution);
        value.set("durationRangeSec", range);
        value.set("unitDensityPolicy", density);
        if(hongguo){ArrayNode presets=value.putArray("seriesPresets");for(int preset:"LONG".equals(family)?new int[]{30,40,50}:new int[]{60,80,100})presets.add(preset);value.put("commercialRulePack","HONGGUO");}
        validate(value);
        return value;
    }

    private void validate(ObjectNode value) {
        String family = value.path("family").asText();
        if (!FAMILIES.contains(family)) throw new IllegalArgumentException("episodeFormat.family 无效：" + family);
        positive(value.path("targetDurationSec").asInt(), "episodeFormat.targetDurationSec");
        if (value.path("targetBeatCount").asInt() < 1 || value.path("sceneLimit").asInt() < 1)
            throw new IllegalArgumentException("episodeFormat 的节拍数和场景上限必须为正整数");
        if ("LONG".equals(family) && (!"DOUBLE_ROUND".equals(value.path("beatMode").asText())
                || !"REQUIRED_AT_HALF".equals(value.path("midHookPolicy").asText())))
            throw new IllegalArgumentException("LONG 制式必须采用双轮推进并设置中段钩子");
    }

    private String inferFamily(int seconds) {
        if (seconds >= 10 && seconds <= 45) return "MICRO";
        if (seconds >= 60 && seconds < 90) return "MANJU";
        if (seconds >= 90 && seconds <= 120) return "STANDARD";
        if (seconds >= 165 && seconds <= 195) return "LONG";
        return "CUSTOM";
    }

    private String profileId(String family, int seconds) {return profileId(family,seconds,"GENERAL");}
    private String profileId(String family, int seconds,String distribution) {
        if(distribution.startsWith("HONGGUO")&&Set.of("MANJU","STANDARD","LONG").contains(family))return "HONGGUO_"+family;
        return switch (family) {
            case "MICRO" -> seconds == 24 ? "MICRO_24S" : "GENERAL_MICRO";
            case "MANJU" -> "GENERAL_MANJU";
            case "STANDARD" -> "GENERAL_STANDARD";
            case "LONG" -> "GENERAL_LONG";
            default -> "CUSTOM_" + seconds + "S";
        };
    }

    private int rangeMin(String family, int seconds) {
        return switch (family) { case "MICRO" -> 10; case "MANJU" -> 60; case "STANDARD" -> 90; case "LONG" -> 165; default -> seconds; };
    }

    private int rangeMax(String family, int seconds) {
        return switch (family) { case "MICRO" -> 45; case "MANJU" -> 90; case "STANDARD" -> 120; case "LONG" -> 195; default -> seconds; };
    }

    private int positive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " 必须为正数");
        return value;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private ObjectNode fingerprint(ObjectNode value) {
        try {
            ObjectNode result = value.deepCopy();
            result.remove("fingerprint");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(result.toString().getBytes(StandardCharsets.UTF_8)));
            return result.put("fingerprint", hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
