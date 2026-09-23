package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single low-level comparison policy used by production, timeline and cross-shot QC. */
@Component
public final class ContinuityCompatibilityEvaluator {
    public List<ProductionModels.Risk> comparePlanned(JsonNode previousEnd, JsonNode currentStart) {
        if (!statePresent(previousEnd)) return List.of(error("PREVIOUS_STATE_MISSING", "previousShot.endState", "连续镜头缺少上一镜计划结束状态"));
        if (!statePresent(currentStart)) return List.of(error("CONTINUITY_STATE_MISSING", "shot.startState", "连续镜头缺少当前镜头计划开始状态"));
        return compareStates(previousEnd, currentStart, true);
    }

    public List<ProductionModels.Risk> compareObserved(JsonNode previousTake, JsonNode currentStart) {
        List<ProductionModels.Risk> risks = new ArrayList<>();
        boolean accepted = previousTake.path("selected").asBoolean()
                && previousTake.path("locked").asBoolean()
                && (previousTake.path("qcPassed").asBoolean() || "PASSED".equals(previousTake.path("qcStatus").asText()));
        if (!accepted) risks.add(error("PREVIOUS_TAKE_NOT_ACCEPTED", "previousTake", "连续镜头的上一条视频必须已选择、锁定并通过 QC"));
        JsonNode observed = previousTake.path("observedState");
        if (!statePresent(observed)) risks.add(error("PREVIOUS_STATE_MISSING", "previousTake.observedState", "连续镜头缺少上一条已接受视频的实际结束状态"));
        if (!statePresent(currentStart)) risks.add(error("CONTINUITY_STATE_MISSING", "shot.startState", "连续镜头缺少当前镜头计划开始状态"));
        if (statePresent(observed) && statePresent(currentStart)) risks.addAll(compareStates(observed, currentStart, false));
        return List.copyOf(risks);
    }

    public List<ProductionModels.Risk> compareStates(JsonNode previousEnd, JsonNode currentStart) {
        return compareStates(previousEnd,currentStart,true);
    }

    private List<ProductionModels.Risk> compareStates(JsonNode previousEnd, JsonNode currentStart,boolean strict) {
        List<ProductionModels.Risk> risks = new ArrayList<>();
        compareField(risks, previousEnd, currentStart, "locationId", "LOCATION_DISCONTINUITY", "locationId",strict);
        compareField(risks, previousEnd, currentStart, "lighting", "LIGHTING_DISCONTINUITY", "lighting",strict);
        previousEnd.path("characters").fields().forEachRemaining(entry -> {
            JsonNode next = currentStart.path("characters").path(entry.getKey());
            if (next.isMissingNode()) {
                risks.add(error("IDENTITY_DISCONTINUITY", "characters." + entry.getKey(), "上一镜人物在下一镜无退场说明却消失"));
                return;
            }
            String path = "characters." + entry.getKey();
            compareField(risks, entry.getValue(), next, "identityId", "IDENTITY_DISCONTINUITY", path,strict);
            compareField(risks, entry.getValue(), next, "lookId", "WARDROBE_DISCONTINUITY", path,strict);
            compareField(risks, entry.getValue(), next, "pose", "POSE_DISCONTINUITY", path,strict);
            compareField(risks, entry.getValue(), next, "motionPhase", "MOTION_PHASE_DISCONTINUITY", path,strict);
            compareField(risks, entry.getValue(), next, "screenDirection", "SCREEN_DIRECTION_DISCONTINUITY", path,strict);
            compareActionState(risks, entry.getValue().path("actionState"), next.path("actionState"), path + ".actionState",strict);
        });
        previousEnd.path("props").fields().forEachRemaining(entry -> {
            JsonNode next = currentStart.path("props").path(entry.getKey());
            if (next.isMissingNode()&&strict)risks.add(error("CONTINUITY_FIELD_MISSING","props."+entry.getKey(),"连续镜头缺少上一镜道具状态"));
            else if(!next.isMissingNode())compareField(risks, entry.getValue(), next, "holder", "PROP_HOLDER_DISCONTINUITY", "props." + entry.getKey(),strict);
        });
        return List.copyOf(risks);
    }

    public List<ProductionModels.Risk> compareBlocking(JsonNode previous, JsonNode current) {
        boolean before = previous.isObject() && !previous.isEmpty(), after = current.isObject() && !current.isEmpty();
        if (before != after) return List.of(error("BLOCKING_STATE_MISSING", "shot.blocking", "连续镜头的相邻 Blocking 状态必须成对存在"));
        if (!before) return List.of();
        List<ProductionModels.Risk> risks = new ArrayList<>();
        String beforeAxis = text(previous, "axis"), afterAxis = text(current, "axis");
        String beforeSide = text(previous, "axisSide"), afterSide = text(current, "axisSide");
        if (!beforeAxis.isBlank() && !afterAxis.isBlank() && !beforeAxis.equals(afterAxis))
            risks.add(error("AXIS_DISCONTINUITY", "shot.blocking.axis", "同一空间内不能改写表演轴线"));
        if (!beforeSide.isBlank() && !afterSide.isBlank() && !beforeSide.equals(afterSide)
                && !"ON_AXIS".equals(beforeSide) && !"ON_AXIS".equals(afterSide) && text(current, "axisChangeReason").isBlank())
            risks.add(error("AXIS_SIDE_DISCONTINUITY", "shot.blocking.axisSide", "跨越 180 度轴线必须记录明确理由"));
        Map<String, String> directions = new HashMap<>();
        for (JsonNode actor : previous.path("characters")) directions.put(text(actor, "characterId"), text(actor, "screenDirection"));
        for (JsonNode actor : current.path("characters")) {
            String id = text(actor, "characterId");
            if (opposite(directions.get(id), text(actor, "screenDirection")))
                risks.add(error("SCREEN_DIRECTION_DISCONTINUITY", "shot.blocking.characters." + id + ".screenDirection", "连续动作的画面运动方向不能无理由反转"));
        }
        return List.copyOf(risks);
    }

    private void compareActionState(List<ProductionModels.Risk> risks, JsonNode previous, JsonNode current, String path,boolean strict) {
        boolean before = previous.isObject() && !previous.isEmpty(), after = current.isObject() && !current.isEmpty();
        if (before != after) risks.add(strict?error("ACTION_STATE_MISSING", path, "连续动作的 ActionState 必须从上一镜结束状态传到下一镜开始状态"):warning("INSUFFICIENT_EVIDENCE",path,"观测画面不足以验证 ActionState"));
        else if (before && !previous.equals(current)) risks.add(error("ACTION_STATE_DISCONTINUITY", path, "连续动作的 ActionState 起点与上一镜实际终点不一致"));
    }

    private static boolean statePresent(JsonNode state) { return state.isObject() && !state.isEmpty(); }
    private static void compareField(List<ProductionModels.Risk> risks, JsonNode left, JsonNode right, String field, String code,String path,boolean strict) {
        String before = left.path(field).asText(""), after = right.path(field).asText("");
        if(!strict&&before.isBlank()&&!after.isBlank())risks.add(warning("INSUFFICIENT_EVIDENCE",path+"."+field,"观测画面不足以验证字段 "+field));
        else if(!before.isBlank()&&after.isBlank())risks.add(strict?error("CONTINUITY_FIELD_MISSING",path+"."+field,"连续镜头缺少应继承字段 "+field):warning("INSUFFICIENT_EVIDENCE",path+"."+field,"观测画面不足以验证字段 "+field));
        else if (!before.isBlank() && !before.equals(after)) risks.add(error(code, path + "." + field, "相邻镜头状态不连续：" + before + " -> " + after));
    }
    private static boolean opposite(String left, String right) { return ("FRAME_LEFT".equals(left) && "FRAME_RIGHT".equals(right)) || ("FRAME_RIGHT".equals(left) && "FRAME_LEFT".equals(right)) || ("INTO_DEPTH".equals(left) && "OUT_OF_DEPTH".equals(right)) || ("OUT_OF_DEPTH".equals(left) && "INTO_DEPTH".equals(right)); }
    private static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private static ProductionModels.Risk error(String code, String path, String message) { return new ProductionModels.Risk(code, "ERROR", path, message); }
    private static ProductionModels.Risk warning(String code,String path,String message){return new ProductionModels.Risk(code,"WARNING",path,message);}
}
