package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic story checks. Semantic quality remains a separate Story QA concern. */
@Component
public class StoryContractValidator {
    public void validateCore(JsonNode core, int episodeCount) {
        List<JsonNode> units = new ArrayList<>();
        core.path("unitArcs").forEach(units::add);
        units.sort(Comparator.comparingInt(unit -> unit.path("startEpisode").asInt()));
        if (units.isEmpty()) throw new IllegalArgumentException("CORE 必须包含覆盖整季的 UnitArc");
        int expected = 1;
        Set<String> ids = new HashSet<>();
        for (JsonNode unit : units) {
            String id = Documents.required(unit, "unitId");
            if (!ids.add(id)) throw new IllegalArgumentException("UnitArc unitId 重复：" + id);
            int start = unit.path("startEpisode").asInt();
            int end = unit.path("endEpisode").asInt();
            if (start < expected) throw new IllegalArgumentException("UnitArc 在第 " + start + " 集发生重叠");
            if (start > expected) throw new IllegalArgumentException("UnitArc 漏掉第 " + expected + " 集");
            if (end < start) throw new IllegalArgumentException("UnitArc 结束集不能早于开始集");
            expected = end + 1;
        }
        if (expected <= episodeCount) throw new IllegalArgumentException("UnitArc 漏掉第 " + expected + " 集及后续集数");
        if (expected != episodeCount + 1) throw new IllegalArgumentException("UnitArc 超出项目总集数 " + episodeCount);
    }

    public void validateOutlineEpisode(JsonNode episode, JsonNode format) {
        requireFormat(episode, format);
        int limit = format.path("sceneLimit").asInt(Integer.MAX_VALUE);
        if (episode.path("scenePlan").size() > limit)
            throw new IllegalArgumentException("本集场景数超过制式上限 " + limit);
        if ("REQUIRED_AT_HALF".equals(format.path("midHookPolicy").asText()) && !episode.path("midHook").isObject())
            throw new IllegalArgumentException("当前长篇制式缺少中段钩子");
        if (episode.path("progressionEvents").isEmpty())
            throw new IllegalArgumentException("本集没有可验证的剧情增量，疑似注水");
    }

    public void validateScript(JsonNode script, JsonNode format) {
        requireFormat(script, format);
        int limit = format.path("sceneLimit").asInt(Integer.MAX_VALUE);
        if (script.path("scenes").size() > limit)
            throw new IllegalArgumentException("剧本场景数超过制式上限 " + limit);
        if ("REQUIRED_AT_HALF".equals(format.path("midHookPolicy").asText()) && !script.path("midHook").isObject())
            throw new IllegalArgumentException("当前长篇剧本缺少中段钩子");
    }

    private void requireFormat(JsonNode document, JsonNode format) {
        if (!format.path("profileId").asText().equals(document.path("episodeFormatId").asText()))
            throw new IllegalArgumentException("内容的 episodeFormatId 与项目已解析制式不一致");
        if (!format.path("beatMode").asText().equals(document.path("beatMode").asText()))
            throw new IllegalArgumentException("内容的 beatMode 与项目已解析制式不一致");
    }
}
