package com.yourapp.drama.provider.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.yourapp.drama.workflow.Documents.obj;
import static com.yourapp.drama.workflow.Documents.text;

/** Deterministic fixture for the same review gates used by live mode. Never used by a live adapter. */
final class StoryDevelopmentDemo {
    static ObjectNode generate(JsonNode input) {
        if ("STORY_QA".equals(text(input, "phase"))) return quality();
        if ("STORY_BRIEF".equals(text(input, "phase"))) return storyBrief(input.path("project"));
        if ("PREMISE".equals(text(input, "phase"))) return premise(input.path("project"));
        JsonNode project = input.path("project");
        JsonNode format = input.path("episodeFormat");
        double seconds = format.path("targetDurationSec").asDouble(project.path("targetDuration").asDouble(20));
        if ("CORE".equals(text(input, "phase"))) return core(project);
        if ("OUTLINE_BATCH".equals(text(input, "phase"))) return outlines(input, format, seconds);
        return script(input, format, seconds);
    }

    private static ObjectNode storyBrief(JsonNode project){
        String idea=project.path("idea").asText("主角追查一条改变命运的线索");
        ObjectNode result=obj().put("originalIdea",idea).put("goal","查清关键线索并作出有代价的选择")
                .put("coreConflict","主角追查真相，对手为保护自身目标持续阻挠")
                .put("failureCost","主角会失去重要关系、信用或保护他人的机会")
                .put("informationGap","观众、主角和对手掌握不同层级的信息")
                .put("endingDirection","主角以主动选择兑现真相和情绪承诺");
        result.set("protagonist",obj().put("name","待由故事圣经命名").put("identity","承受当前困境的人").put("goal","查清线索并保护重要的人"));
        result.set("opponent",obj().put("name","待由故事圣经命名").put("identity","有独立利益的阻挠者").put("goal","让关键事实继续被隐藏"));
        result.putArray("hardConstraints").add("集数="+project.path("episodeCount").asInt(1)).add("单集秒数="+project.path("targetDuration").asDouble(24));
        result.putArray("mustKeep").add(idea);result.putArray("mustNotChange").add("用户明确的人物、题材、篇幅和结局约束");return result;
    }

    private static ObjectNode premise(JsonNode project) {
        int episodes = project.path("episodeCount").asInt(1);
        ObjectNode result = obj().put("viable", episodes <= 30)
                .put("coreConflict", "主角追查关键线索，对手持续转移证据并保护自己的目标")
                .put("protagonistGoal", "在代价扩大前查清线索并保护相关人物")
                .put("opposition", "掌握资源且有自身利益的阻挠者")
                .put("audiencePromise", "每次选择都改变嫌疑方向、人物关系与风险")
                .put("whyNotResolveImmediately", "线索分散在不同人物和地点，对手会根据主角行动调整策略");
        result.set("expansionPotential", obj().put("conflictDepth", "阻挠从隐瞒升级为资源和安全压力")
                .put("characterDepth", "主角要克服独自承担的决策弱点")
                .put("relationshipDepth", "盟友关系会因证据与代价改变")
                .put("reversalPotential", "旧线索会因新证据获得不同解释")
                .put("informationDepth", "观众、主角和对手拥有不同层级的信息"));
        var risks = result.putArray("risks");
        if (episodes > 30) risks.add("原始创意容量低于目标集数，CORE 必须建立多个不同目标的 Unit，不能重复同一阻挠");
        result.putArray("questionsForCore").add("每个 Unit 将改变哪一种目标、关系、资源、知识或风险状态？")
                .add("对手在每个阶段为什么采取不同且合理的行动？");
        return result;
    }

    private static ObjectNode core(JsonNode project) {
        int episodeCount = project.path("episodeCount").asInt(1);
        ObjectNode core = obj().put("title", project.path("name").asText("演示故事"))
                .put("logline", project.path("idea").asText("主角追查一条线索"));
        core.set("storyProfile", project.path("storyProfile").deepCopy());
        core.set("emotionContract", obj().put("corePromise", "每次选择都让真相更近、代价更高")
                .put("primaryEmotion", "期待").put("secondaryEmotion", "紧张")
                .put("audienceExpectation", "看到线索如何改变人物关系和选择")
                .put("payoffPattern", "线索出现→选择→代价→阶段兑现"));
        core.withObject("emotionContract").putArray("forbiddenPatterns").add("只换台词、不改变状态");
        core.set("storyEngine", obj().put("coreConflict", "周伯追查线索而阻挠者试图销毁它")
                .put("protagonistGoal", "查清旧钥匙的来源").put("oppositionGoal", "让柜门永远不被打开")
                .put("stakes", "邻里安全与周伯的信用").put("mainPayoff", "周伯用选择揭开真相")
                .put("reversalStrategy", "每条线索都改变嫌疑方向"));
        core.withObject("storyEngine").putArray("escalationAxes").add("知识").add("风险").add("关系");
        core.putArray("worldRules").add("事件遵守同一时间线，秘密只由在场者获知");
        core.set("seasonArc", obj().put("opening", "周伯发现旧钥匙和新划痕").put("development", "追查钥匙经过的每个人")
                .put("majorTurn", "可信的伙伴被证据指向").put("climax", "周伯必须在保护伙伴与公开真相之间选择")
                .put("ending", "柜门打开，真相兑现且关系被重建"));
        ObjectNode unit = obj().put("unitId", "UNIT_01").put("startEpisode", 1).put("endEpisode", episodeCount)
                .put("title", "旧钥匙").put("goal", "查清钥匙来源").put("mainConflict", "追查与阻挠")
                .put("antagonistPressure", "线索持续被转移").put("emotionGoal", "期待逐步转成紧张并兑现")
                .put("reveal", "钥匙能打开失踪者留下的柜门").put("payoff", "确认柜门位置")
                .put("climax", "周伯决定当众开门").put("endHook", "柜中物证指向更大秘密");
        unit.set("unitTransformation", obj().put("protagonist", "从独自承担到信任伙伴")
                .put("relationships", "邻里从猜疑到共同承担").put("mainConflict", "从找钥匙升级为保护证据")
                .put("audienceKnowledge", "知道钥匙与失踪案相连").put("nextStageReason", "物证来源仍未查清"));
        core.putArray("unitArcs").add(unit);

        ObjectNode person = obj().put("characterKey", "lead").put("name", "周伯")
                .put("description", "珍惜邻里情分、习惯独自承担问题的老人");
        ObjectNode narrative = obj().put("storyRole", "主角").put("want", "查清钥匙来源")
                .put("need", "学会向邻里求助").put("fear", "连累别人").put("weakness", "凡事独自承担")
                .put("secret", "曾替失踪者保管过包裹").put("motivation", "弥补当年的失约")
                .put("decisionPattern", "先保护别人，再承担风险").put("speechStyle", "短句、乡土称谓、回避自我解释");
        narrative.set("arc", obj().put("start", "独自追查").put("end", "主动信任伙伴"));
        narrative.withObject("arc").putArray("turningPoints").add("邻居因保护他受伤");
        narrative.putArray("behaviorRules").add("遇到危险先护住在场老人");
        narrative.putArray("relationships").add("与邻里从互相隐瞒到共同追查");
        person.set("narrativeBible", narrative);
        person.set("identityTraits", obj().put("age", "六十五岁").put("face", "方脸，左眉有一道短疤")
                .put("hair", "灰白短发").put("body", "偏瘦，身高一米七")
                .put("voiceDialect", project.path("dialect").asText("MANDARIN")));
        person.putArray("looks").add(obj().put("lookKey", "workwear").put("name", "田间装")
                .put("description", "靛蓝棉布外套、深灰长裤、黑布鞋，袖口磨白"));
        core.putArray("characters").add(person);
        ObjectNode location = obj().put("locationKey", "yard").put("name", "村口院子").put("description", "石墙围合的院子，南门通向土路");
        ObjectNode locationBible=obj().put("layout","长方形石墙院落，南门、北侧屋檐、东侧水井");
        locationBible.set("coordinateSystem",obj().put("origin","院落中心地面").put("northAxis","朝北侧屋檐为北").put("eastAxis","朝水井为东").put("verticalAxis","垂直地面向上"));
        locationBible.set("dimensions",obj().put("width","东西八米").put("depth","南北十米").put("height","围墙三米"));
        locationBible.putArray("surfaces").add(obj().put("surfaceId","GROUND").put("name","院落地面").put("kind","GROUND").put("worldOrientation","HORIZONTAL").put("bounds","东西八米、南北十米").put("material","青石").put("appearance","灰黑、有细小裂缝"))
                .add(obj().put("surfaceId","SOUTH_WALL").put("name","南墙").put("kind","WALL").put("worldOrientation","SOUTH").put("bounds","宽八米、高三米").put("material","旧石砖").put("appearance","中部嵌木门"));
        locationBible.putArray("fixedFeatures").add(obj().put("featureId","SOUTH_GATE").put("name","南门").put("kind","DOOR").put("supportSurfaceId","SOUTH_WALL").put("worldPosition","南墙正中").put("size","宽两米、高二点四米").put("state","关闭").put("appearance","旧双扇木门"))
                .add(obj().put("featureId","EAST_WELL").put("name","水井").put("kind","WELL").put("supportSurfaceId","GROUND").put("worldPosition","原点以东三米").put("size","直径一米、高零点八米").put("state","井盖半掩").put("appearance","青石井圈"));
        locationBible.putArray("spatialRelations").add(obj().put("subjectId","SOUTH_GATE").put("relation","SOUTHWEST_OF").put("objectId","EAST_WELL").put("distance","约五米"));
        locationBible.putArray("lightSources").add(obj().put("lightId","WEST_SUN").put("kind","SUN").put("worldPosition","西侧天空").put("direction","由西向东").put("colorTemperature","暖色夕阳").put("appearance","低角度斜射"));
        locationBible.putArray("visualInvariants").add("南门始终位于南墙正中").add("水井始终位于院落东侧");locationBible.putArray("prohibitedElements").add("牌位");
        location.set("locationBible",locationBible);
        core.putArray("locations").add(location);
        ObjectNode prop = obj().put("propKey", "key").put("name", "旧钥匙").put("description", "带圆环的锈铁钥匙").put("state", "周伯持有，完好");
        prop.set("propBible", obj().put("appearance", "铁灰色、齿部有两道缺口").put("scale", "长六厘米").put("ownership", "周伯右侧衣袋"));
        core.putArray("props").add(prop);
        core.putArray("foreshadowingRules").add("旧钥匙开场出现，终局打开关联柜门");
        core.putArray("continuityRules").add("换装、受伤与钥匙移交必须写出原因");
        return core;
    }

    private static ObjectNode outlines(JsonNode input, JsonNode format, double seconds) {
        ObjectNode out = obj();
        var cards = out.putArray("episodes");
        String start = input.path("sourceSnapshot").path("requiredStartState").asText("黄昏，周伯穿田间装站在院内，钥匙在右侧衣袋");
        for (int n = input.path("startEpisode").asInt(); n <= input.path("endEpisode").asInt(); n++) {
            String end = "第 " + n + " 集结束：周伯仍穿田间装，持有旧钥匙，并获得第 " + n + " 条线索";
            ObjectNode card = obj().put("episodeNo", n).put("title", "第 " + n + " 集 · 线索")
                    .put("episodeFunction", "改变钥匙来源的判断").put("episodeGoal", "获得一条可行动的新线索")
                    .put("hook", "钥匙上出现不属于周伯的泥迹").put("mainConflict", "周伯要查泥迹，阻挠者试图擦除")
                    .put("newInformation", "泥迹只来自废弃祠堂").put("characterDecision", "周伯决定带邻居共同前往")
                    .put("escalation", "阻挠者开始监视院门").put("payoff", "确认泥迹来源")
                    .put("cliffhanger", "祠堂内传出刚停止的脚步声").put("summary", "周伯在阻碍中作出选择并改变嫌疑方向")
                    .put("startState", start).put("endState", end);
            addFormat(card, format, seconds, "beats");
            refs(card);
            card.set("foreshadowing", obj());
            card.withObject("foreshadowing").putArray("plant");
            card.withObject("foreshadowing").putArray("advance").add("钥匙泥迹");
            card.withObject("foreshadowing").putArray("resolve");
            card.putArray("progressionEvents").add(obj().put("atSec", Math.max(1, seconds / 2)).put("type", "NEW_INFORMATION").put("description", "确认泥迹来源"));
            card.putArray("scenePlan").add(scene(seconds));
            cards.add(card);
            start = end;
        }
        return out;
    }

    private static ObjectNode script(JsonNode input, JsonNode format, double seconds) {
        JsonNode outline = input.path("sourceSnapshot").path("episodeOutline");
        ObjectNode out = obj().put("title", text(outline, "title")).put("summary", text(outline, "summary"))
                .put("startState", text(outline, "startState")).put("endState", text(outline, "endState"))
                .put("script", "【本地演示剧本】黄昏，村口院子。周伯穿靛蓝棉布外套，右手按住口袋里的旧钥匙。他在南门停步，听见北侧屋檐下传来敲击声，先回头确认身后没有人，再慢慢走向柜门。周伯：这把钥匙留了这么久，今天总要试一试。他举起钥匙，却在锁孔边发现一道新鲜划痕，手停在半空，决定先查清谁刚刚来过。")
                .put("targetDurationSec", seconds);
        addFormat(out, format, seconds, "beatBoundaries");
        refs(out);
        out.putArray("scenes").add(scene(seconds));
        return out;
    }

    private static void addFormat(ObjectNode value, JsonNode format, double seconds, String beatsField) {
        value.put("episodeFormatId", format.path("profileId").asText()).put("beatMode", format.path("beatMode").asText());
        if ("beats".equals(beatsField)) value.put("estimatedDurationSec", seconds);
        value.putArray(beatsField)
                .add(beat("OPENING_HOOK", 0, Math.min(3, seconds)))
                .add(beat("PAYOFF_AND_ENDPOINT", Math.min(3, seconds), seconds));
        if ("REQUIRED_AT_HALF".equals(format.path("midHookPolicy").asText())) {
            value.set("midHook", obj().put("required", true).put("preferredPositionRatio", 0.5)
                    .put("type", "NEW_THREAT").put("description", "阻挠者当面提高代价")
                    .put("raisesWhat", "risk").put("mustNotResolveMainPayoff", true));
        }
    }

    private static ObjectNode beat(String id, double start, double end) {
        return obj().put("beatId", id).put("purpose", id.equals("OPENING_HOOK") ? "建立观看问题" : "兑现本集变化并留下后续问题")
                .put("startSec", start).put("endSec", end);
    }

    private static void refs(ObjectNode value) {
        value.putArray("characterKeys").add("lead");
        value.putArray("locationKeys").add("yard");
        value.putArray("propKeys").add("key");
    }

    private static ObjectNode scene(double seconds) {
        return obj().put("name", "院内追查").put("description", "周伯穿田间装，带旧钥匙从南门走到北侧屋檐，发现划痕并停步观察。")
                .put("startSec", 0).put("endSec", seconds).put("duration", seconds);
    }

    private static ObjectNode quality(){
        ObjectNode qa=obj().put("passed",true).put("watchReason","人物发现新线索并作出会增加风险的选择")
                .put("nextEpisodeReason","新证据改变嫌疑方向").put("rewriteRequired",false);
        ObjectNode scores=qa.putObject("dimensions");
        for(String key:java.util.List.of("hook","progression","conflict","characterConsistency","genreFit","continuity","payoff","cliffhanger","narrativeNecessity","dialogueNaturalness"))scores.put(key,85);
        ObjectNode delta=qa.putObject("episodeDelta");
        for(String key:java.util.List.of("factsChanged","relationshipsChanged","goalsChanged","knowledgeChanged","riskChanged","resourcesChanged"))delta.putArray(key);
        delta.withArray("knowledgeChanged").add("周伯确认钥匙的新线索");
        qa.putArray("blockingIssues");qa.putArray("issues");qa.putArray("rewriteInstructions");return qa;
    }
}
