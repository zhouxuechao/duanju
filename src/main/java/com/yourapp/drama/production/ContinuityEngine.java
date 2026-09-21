package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.production.ProductionJson.*;
import static com.yourapp.drama.production.ProductionModels.*;

@Service
public class ContinuityEngine {
    private final ObjectMapper mapper;
    private final ShotComplexityValidator complexity=new ShotComplexityValidator();
    public ContinuityEngine(ObjectMapper mapper) { this.mapper = mapper; }

    public ContinuityPlan plan(JsonNode request) {
        Shot shot = shot(request, mapper);
        JsonNode raw = shotNode(request);
        JsonNode assets = request.path("assets");
        JsonNode previous = request.path("previousState");
        if (!previous.isObject()) previous = request.path("previousShot").path("endState");
        List<Risk> risks = new ArrayList<>();
        validateAtomicShot(shot, raw, risks);
        risks.addAll(complexity.validate(raw));
        Set<String> required = new LinkedHashSet<>();
        ObjectNode constraints = mapper.createObjectNode();
        ObjectNode inherited = mapper.createObjectNode();
        boolean spatial = shot.relationToPrevious() != ShotRelation.LOCATION_CHANGE && shot.relationToPrevious() != ShotRelation.TIME_JUMP;
        boolean continuous = shot.relationToPrevious() == ShotRelation.CONTINUOUS;
        List<String> allowed = new ArrayList<>(List.of("framing", "cameraAngle", "cameraMovement", "expression", "composition"));
        if (!spatial) allowed.addAll(List.of("position", "pose", "time", "lighting", "location"));
        if (previous.isObject()) {
            for (String field : List.of("time", "lighting", "locationId", "spatialRelations"))
                if (spatial && previous.has(field)) inherited.set(field, previous.path(field).deepCopy());
            ObjectNode inheritedCharacters = inherited.putObject("characters");
            previous.path("characters").fields().forEachRemaining(e -> {
                ObjectNode character = mapper.createObjectNode();
                for (String field : List.of("identityId", "lookId", "alive", "currentLocation", "currentGoal", "physicalCondition", "injuries", "emotionalState", "clothingState", "makeupState", "hairState", "carriedProps", "leftHandProp", "rightHandProp", "holding"))
                    if (e.getValue().has(field)) character.set(field, e.getValue().path(field).deepCopy());
                if (spatial) {
                    for (String field : List.of("position", "lookDirection", "pose", "actionState"))
                        if (e.getValue().has(field) && ContinuityStatePolicy.inherits("characters."+e.getKey()+"."+field,shot.relationToPrevious())) character.set(field, e.getValue().path(field));
                }
                inheritedCharacters.set(e.getKey(), character);
            });
            if (previous.path("props").isObject()) {
                ObjectNode props=inherited.putObject("props");
                previous.path("props").fields().forEachRemaining(prop->{
                    ObjectNode value=props.putObject(prop.getKey());
                    prop.getValue().fields().forEachRemaining(field->{
                        boolean worldPlacement=Set.of("position","location").contains(field.getKey());
                        if(!worldPlacement||spatial)value.set(field.getKey(),field.getValue().deepCopy());
                    });
                });
            }
        } else if (continuous) error(risks, "PREVIOUS_STATE_REQUIRED", "previousState", "连续动作需要上一锁定镜头的结束状态");

        ObjectNode identityConstraints = constraints.putObject("characters");
        for (String id : shot.characterIds()) {
            required.add("character:" + id);
            JsonNode asset = findAsset(assets, "characters", id);
            if (asset.isMissingNode() || asset.isNull()) {
                error(risks, "CHARACTER_ASSET_MISSING", "assets.characters." + id, "缺少角色身份资产 " + id); continue;
            }
            ObjectNode locked = identityConstraints.putObject(id);
            locked.put("identityId",id);
            for (String field : List.of("name", "identityTraits", "baseLookId")) if (asset.has(field)) locked.set(field, asset.path(field));
            String lookId = text(shot.startState().path("characters").path(id), "lookId");
            if (lookId.isBlank()) lookId = text(inherited.path("characters").path(id), "lookId");
            if (lookId.isBlank()) lookId = text(asset, "baseLookId");
            if (lookId.isBlank()) error(risks, "LOOK_MISSING", "shot.startState.characters." + id, "角色需要明确的当前服装 lookId");
            else {
                required.add("look:" + lookId);
                locked.put("lookId", lookId);
                JsonNode look = findAsset(assets, "looks", lookId);
                if (look.isMissingNode() || look.isNull()) error(risks, "LOOK_ASSET_MISSING", "assets.looks." + lookId, "缺少当前定妆资产 " + lookId);
                else { locked.set("wardrobe", look); requireAnchorSet(look,"assets.looks."+lookId,risks); if(!id.equals(text(look,"characterId")))error(risks,"LOOK_OWNER_MISMATCH","shot.startState.characters."+id+".lookId","定妆不属于当前人物"); }
            }
            if (!inherited.path("characters").isObject()) inherited.putObject("characters");
            ObjectNode chars = (ObjectNode) inherited.path("characters");
            ObjectNode character = chars.path(id).isObject() ? (ObjectNode) chars.path(id) : chars.putObject(id);
            if (!character.has("lookId") && !lookId.isBlank()) character.put("lookId", lookId);
            if (!character.has("identityId")) character.put("identityId",id);
            String requestedIdentity = text(shot.startState().path("characters").path(id), "identityId");
            if (!requestedIdentity.isBlank() && !requestedIdentity.equals(id))
                error(risks, "IDENTITY_DRIFT", "shot.startState.characters." + id + ".identityId", "镜头身份与故事圣经不一致");
        }
        if (shot.locationId().isBlank()) error(risks, "LOCATION_REQUIRED", "shot.locationId", "镜头必须指定场景资产");
        else {
            required.add("location:" + shot.locationId());
            JsonNode location = findAsset(assets, "locations", shot.locationId());
            if (location.isMissingNode() || location.isNull()) error(risks, "LOCATION_ASSET_MISSING", "assets.locations." + shot.locationId(), "缺少场景资产");
            else { constraints.set("location", location); requireAnchorSet(location,"assets.locations."+shot.locationId(),risks); }
            if (spatial && inherited.has("locationId") && !shot.locationId().equals(text(inherited, "locationId")))
                error(risks, "LOCATION_DRIFT", "shot.locationId", "场景变化需要 LOCATION_CHANGE 关系");
        }
        ObjectNode propConstraints = constraints.putObject("props");
        for (String id : shot.propIds()) {
            required.add("prop:" + id);
            JsonNode prop = findAsset(assets, "props", id);
            if (prop.isMissingNode() || prop.isNull()) error(risks, "PROP_ASSET_MISSING", "assets.props." + id, "缺少道具资产 " + id);
            else { propConstraints.set(id, prop); requireAnchorSet(prop,"assets.props."+id,risks); }
        }

        compareInherited(inherited, shot.startState(), "shot.startState", raw.path("authorizedChanges"), risks);
        ObjectNode start = deepMerge(mapper, inherited, shot.startState());
        start.put("locationId", shot.locationId());
        constraints.set("state", inherited);
        for(String characterId:shot.characterIds())if(start.path("characters").path(characterId).has("alive")&&!start.path("characters").path(characterId).path("alive").asBoolean())
            error(risks,"DEAD_CHARACTER_PRESENT","shot.startState.characters."+characterId+".alive","已死亡角色不能作为当前镜头中的正常出场人物");
        validateHolders(start, risks);
        if (shot.relationToPrevious() == ShotRelation.REVERSE_SHOT && sameComposition(request.path("previousShot"), raw))
            risks.add(new Risk("DUPLICATE_COMPOSITION", "WARNING", "shot.shotSize", "正反打应按分镜改变机位、视线或构图；不要复制上一戏剧画面"));
        if (shot.difficulty() == Difficulty.D) risks.add(new Risk("HIGH_DIFFICULTY", "WARNING", "shot.difficulty", "多人或复杂动作应拆镜或提供动作参考"));
        int score = Math.min(100, risks.stream().mapToInt(r -> r.severity().equals("ERROR") ? 25 : 10).sum());
        return new ContinuityPlan(start, constraints, Collections.unmodifiableSet(required), List.copyOf(allowed), List.copyOf(risks), score, !hasErrors(risks));
    }

    public JsonNode applyLockedTake(JsonNode request) {
        if (!request.path("locked").asBoolean(false) || !request.path("qcPassed").asBoolean(false))
            throw new IllegalArgumentException("只有通过 QC 并锁定的 Take 才能更新 Scene State");
        JsonNode end = request.path("endState");
        if (!end.isObject() || end.isEmpty()) throw new IllegalArgumentException("锁定 Take 必须提供已复核的 endState");
        List<Risk> risks = new ArrayList<>();
        validateHolders(end, risks);
        if (hasErrors(risks)) throw new IllegalArgumentException("结束状态道具归属冲突: " + risks);
        return deepMerge(mapper, request.path("sceneState"), end);
    }

    private void compareInherited(JsonNode inherited, JsonNode proposed, String path, JsonNode changes, List<Risk> risks) {
        if (!proposed.isObject()) return;
        proposed.fields().forEachRemaining(e -> {
            if (!inherited.has(e.getKey())) return;
            String fieldPath = path + "." + e.getKey();
            JsonNode expected = inherited.path(e.getKey());
            if (expected.isObject() && e.getValue().isObject()) compareInherited(expected, e.getValue(), fieldPath, changes, risks);
            else if (!expected.equals(e.getValue())) {
                boolean identity = "identityId".equals(e.getKey());
                String relativePath = fieldPath.substring("shot.startState.".length());
                boolean authorized = false;
                if (changes.isArray()) for (JsonNode change : changes) {
                    if (relativePath.equals(text(change, "path")) && !text(change, "reason").isBlank()) authorized = true;
                }
                if (identity || !authorized) error(risks, identity ? "IDENTITY_DRIFT" : "STATE_CONFLICT", fieldPath,
                    "继承状态 " + expected + " 与请求 " + e.getValue() + " 不一致" + (identity ? "，角色身份不可换" : "，需要脚本明确的 authorizedChanges 原因"));
            }
        });
    }

    private void validateHolders(JsonNode state, List<Risk> risks) {
        state.path("characters").fields().forEachRemaining(e -> {
            String holding = text(e.getValue(), "holding");
            if (!holding.isBlank() && !"null".equals(holding) && !state.path("props").has(holding))
                error(risks, "PROP_HOLDING_MISSING", "characters." + e.getKey() + ".holding", "人物持有的道具不存在于本镜头状态");
            for(String hand:List.of("leftHandProp","rightHandProp")){
                String prop=text(e.getValue(),hand);if(!prop.isBlank()&&!"null".equals(prop)&&!state.path("props").has(prop))
                    error(risks,"PROP_HOLDING_MISSING","characters."+e.getKey()+"."+hand,"人物手持的道具不存在于本镜头状态");
            }
            for(JsonNode prop:e.getValue().path("carriedProps"))if(!state.path("props").has(prop.asText()))
                error(risks,"PROP_HOLDING_MISSING","characters."+e.getKey()+".carriedProps","人物携带的道具不存在于本镜头状态");
        });
        state.path("props").fields().forEachRemaining(e -> {
            String holder = text(e.getValue(), "carriedBy");if(holder.isBlank())holder=text(e.getValue(),"holder");
            if (!holder.isBlank() && !"null".equals(holder)) {
                if (!state.path("characters").has(holder)) error(risks, "PROP_HOLDER_MISSING", "props." + e.getKey(), "持有道具的角色不存在于场景状态");
                else {
                    String holding = text(state.path("characters").path(holder), "holding");
                    String hand=text(e.getValue(),"heldByHand");String handProp="LEFT".equals(hand)?text(state.path("characters").path(holder),"leftHandProp"):"RIGHT".equals(hand)?text(state.path("characters").path(holder),"rightHandProp"):"";
                    if ((!holding.isBlank() && !holding.equals(e.getKey()))||(!handProp.isBlank()&&!handProp.equals(e.getKey()))) error(risks, "PROP_HOLDER_CONFLICT", "props." + e.getKey(), "人物持物记录与道具持有人或手位不一致");
                }
            }
        });
    }

    private boolean sameComposition(JsonNode previous, JsonNode current) {
        return previous.isObject() && List.of("shotSize", "cameraAngle", "composition").stream().allMatch(k -> previous.path(k).equals(current.path(k)));
    }
    private void requireAnchorSet(JsonNode asset,String path,List<Risk> risks){
        JsonNode views=asset.path("approvedViews");Set<String> names=new HashSet<>();Set<Integer> versions=new HashSet<>();
        if(views.isArray())for(JsonNode view:views){if(!view.path("approved").asBoolean()||view.path("stale").asBoolean())continue;names.add(text(view,"view"));versions.add(view.path("setVersion").asInt());}
        if(names.size()!=4||versions.size()!=1)error(risks,"ANCHOR_REVIEW_REQUIRED",path,"必须先生成并批准同一版本的四张素材视图");
    }
    private void validateAtomicShot(Shot shot, JsonNode raw, List<Risk> risks) {
        if (!Double.isFinite(shot.duration()) || shot.duration() < 2 || shot.duration() > 5)
            error(risks, "ATOMIC_DURATION", "shot.duration", "原子镜头必须在 2 至 5 秒之间；长对白或复杂动作请拆镜");
        if (shot.action().isBlank() || shot.action().length() > 180)
            error(risks, "ATOMIC_ACTION", "shot.action", "提供 180 字以内的一项主要动作，不能传大段小说");
        if (shot.shotSize().isBlank() || shot.cameraAngle().isBlank() || shot.cameraMovement().isBlank())
            error(risks, "CAMERA_REQUIRED", "shot", "必须提供景别、角度与一种主要运镜（静态为 STATIC）");
        if (shot.cameraMovement().matches("(?s).*[,+、/;].*")) error(risks, "MULTIPLE_CAMERA_MOVEMENTS", "shot.cameraMovement", "一个镜头最多一种主要运镜");
        JsonNode cameraPlan=raw.path("cameraPlan");
        if(!cameraPlan.isObject()) error(risks,"CAMERA_PLAN_REQUIRED","shot.cameraPlan","必须记录完整机位方案");
        else for(String field:List.of("position","height","distance","horizontalAngle","verticalAngle","subjectPlacement","focusPoint","depthOfField","lightingDirection")) if(text(cameraPlan,field).isBlank()) error(risks,"CAMERA_PLAN_INCOMPLETE","shot.cameraPlan."+field,"机位方案缺少"+field);
        if(!cameraPlan.path("lensMm").isNumber()||cameraPlan.path("lensMm").asDouble()<14||cameraPlan.path("lensMm").asDouble()>200)error(risks,"CAMERA_LENS_INVALID","shot.cameraPlan.lensMm","镜头焦段须为14至200毫米的数值");
        if (raw.path("actions").isArray() && raw.path("actions").size() > 1) error(risks, "MULTIPLE_ACTIONS", "shot.actions", "一个镜头只允许一个主要动作");
        if (raw.path("visualFocus").isArray() || raw.path("cameraMovement").isArray()) error(risks, "ATOMIC_FOCUS", "shot", "视觉重点和主要运镜必须为单值");
        if (new HashSet<>(shot.characterIds()).size() != shot.characterIds().size()) error(risks, "DUPLICATE_CHARACTER", "shot.characterIds", "同一身份只能出现一次");
    }
}
