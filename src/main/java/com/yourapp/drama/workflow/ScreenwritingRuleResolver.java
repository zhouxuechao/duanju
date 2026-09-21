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
import java.util.Map;
import java.util.LinkedHashMap;

/** Builds a traceable rule pack from data dimensions, without genre-specific execution branches. */
@Component
public class ScreenwritingRuleResolver {
    private static final Map<String, String> TYPE_RULES = Map.ofEntries(
            Map.entry("GROWTH", "TYPE_GROWTH"), Map.entry("SUSPENSE", "TYPE_SUSPENSE"),
            Map.entry("ROMANCE", "TYPE_ROMANCE"), Map.entry("REVENGE", "TYPE_REVENGE"),
            Map.entry("SURVIVAL", "TYPE_SURVIVAL"), Map.entry("COMEDY", "TYPE_COMEDY"),
            Map.entry("FAMILY", "TYPE_FAMILY"), Map.entry("MYSTERY", "TYPE_MYSTERY"),
            Map.entry("ADVENTURE", "TYPE_ADVENTURE"), Map.entry("OTHER", "TYPE_OTHER"));
    private static final Map<String, ObjectNode> STORY_TYPES = storyTypes();

    public ObjectNode resolve(String phase, JsonNode storyProfile, JsonNode episodeFormat, String distributionProfile) {
        String normalizedPhase = normalize(phase, "STORY");
        String storyType = normalize(storyProfile.path("storyType").asText(), "OTHER");
        String family = normalize(episodeFormat.path("family").asText(), "CUSTOM");
        String distribution = normalize(distributionProfile, "GENERAL");
        ArrayNode rules = JsonNodeFactory.instance.arrayNode();
        add(rules, "BASE_" + normalizedPhase, "BASE", "内置基础规则");
        String canonicalType=STORY_TYPES.containsKey(storyType)?storyType:alias(storyType);
        add(rules, STORY_TYPES.containsKey(canonicalType)?"TYPE_"+canonicalType:TYPE_RULES.getOrDefault(storyType,"TYPE_OTHER"), "STORY_TYPE", storyType);
        add(rules, "FORMAT_" + family, "EPISODE_FORMAT", episodeFormat.path("profileId").asText(family));
        if (!"GENERAL".equals(distribution)) add(rules, "DIST_" + distribution, "DISTRIBUTION", distribution);
        for (JsonNode trope : storyProfile.path("tropes")) add(rules, "TROPE_" + normalize(trope.asText(), "OTHER"), "TROPE", trope.asText());

        StringBuilder identity = new StringBuilder(normalizedPhase);
        rules.forEach(rule -> identity.append('|').append(rule.path("ruleId").asText()));
        ObjectNode result = Documents.obj().put("phase", normalizedPhase).put("storyType", storyType)
                .put("episodeFormatId", episodeFormat.path("profileId").asText()).put("distributionProfile", distribution)
                .put("fingerprint", sha256(identity.toString()));
        result.set("rules", rules);
        result.set("storyTypeRule", STORY_TYPES.getOrDefault(storyType,
                STORY_TYPES.getOrDefault(alias(storyType), STORY_TYPES.get("OTHER"))).deepCopy());
        ArrayNode sources = JsonNodeFactory.instance.arrayNode();
        addSource(sources, "references/screenplay-compliance-rules.md", "所有创作阶段的剧作合规底线");
        if ("CORE".equals(normalizedPhase)) {
            addSource(sources, "references/character-bible.md", "人物叙事圣经");
            addSource(sources, "references/emotion-flow-roundtrip.md", "情绪契约与单元推进");
        } else if ("OUTLINE".equals(normalizedPhase) || "OUTLINE_BATCH".equals(normalizedPhase)) {
            addSource(sources, "references/golden-3s-hook-library.md", "按功能选择开场钩子");
            addSource(sources, "references/cliffhanger-master-formulas.md", "按新增问题设计断章");
            addSource(sources, "references/emotion-flow-roundtrip.md", "单元和单集情绪推进");
            addSource(sources, "templates/episode-format.md", "按篇幅组织节拍");
        } else if ("SCRIPT".equals(normalizedPhase) || "EPISODE_SCRIPT".equals(normalizedPhase) || "STORY_QA".equals(normalizedPhase)) {
            addSource(sources, "references/dialogue-doctor-anti-ai.md", "对白自然度与人物语言指纹");
            addSource(sources, "references/golden-3s-hook-library.md", "开场观看问题");
            addSource(sources, "references/cliffhanger-master-formulas.md", "集尾观看动力");
            addSource(sources, "templates/episode-format.md", "正文节拍和长篇双回合");
        }
        if (distribution.startsWith("HONGGUO"))
            addSource(sources, "references/hongguo-beat-sheet.md", "仅此发行配置启用的平台节拍");
        result.set("upstreamSources", sources);
        return result;
    }

    private void add(ArrayNode rules, String ruleId, String category, String source) {
        rules.add(Documents.obj().put("ruleId", ruleId).put("category", category).put("source", source));
    }

    private void addSource(ArrayNode sources, String path, String purpose) {
        sources.add(Documents.obj().put("path", path).put("purpose", purpose)
                .put("upstreamCommit", "edd0df754320c2f3949fb198cea7847c71d0cde0"));
    }

    private static String alias(String type) {
        return switch (type) {
            case "SUSPENSE", "MYSTERY" -> "SUSPENSE_MYSTERY";
            case "ROMANCE" -> "SWEET_ROMANCE";
            case "FAMILY" -> "FAMILY_ETHICS";
            default -> type;
        };
    }

    private static Map<String, ObjectNode> storyTypes() {
        Map<String, ObjectNode> map = new LinkedHashMap<>();
        map.put("COUNTERATTACK", type("让观众看到被低估者以有代价的成长夺回主动权", "低估与压制→突破→小胜→对手升级→能力或资源升级→大反转→兑现", "能力,资源,敌方压力,公众认知", "每个单元改变能力与对手层级", "不公压制,能力缺口,资源限制", "突破后果,能力应用,公众认知反转", "更高层对手,胜利代价,新限制", "只换人辱骂;无限开挂无代价", "胜利是否改变资源、风险或对手层级"));
        map.put("IDENTITY_REVERSAL", type("让误判主角的人逐层发现判断错误并承担后果", "隐藏身份→误判→弱证据→合理否认→强证据→身份层升级→更高冲突→核心曝光", "身份揭露,证据,相信程度,对手层级", "每个单元揭开一层身份并提高否认成本", "身份反差,误判行为,观众信息差", "证据兑现,态度改变,身份层升级", "更强证据,新身份层,误判代价", "第一集全曝;反派永远无脑不信;同一种打脸循环", "身份层是否升级且否认仍有合理依据"));
        map.put("REVENGE", type("让观众看到受害者用准备与选择逐步夺回公正", "受害或失去→准备→突破口→小反击→敌方反击→证据或资源升级→真相→总反攻", "复仇进度,证据,资源,风险,敌方压力", "每个单元完成一个阶段目标并引来更强反击", "未偿还的损失,突破口,敌方动向", "阶段反击,证据闭环,资源夺回", "敌方反击,证据风险,更深真相", "回归即碾压;敌人不反击;复仇没有代价", "反击是否付出代价并引起敌方有效回应"));
        map.put("SWEET_ROMANCE", type("让关系因具体选择而靠近并获得情绪兑现", "接触→吸引→拉近→阻力→选择→关系升级→情绪兑现", "关系阶段,信任,情感距离,相互理解", "每个单元跨过一个关系门槛", "关系反差,共同目标,未说出口的在意", "信任行动,关系确认,相互理解", "必须作出的关系选择,误解的新信息", "人为误会拖集;孤立发糖不推进关系;套用男频打脸", "甜点是否改变关系、认知或选择"));
        map.put("FAMILY_ETHICS", type("让家庭成员在利益与责任压力下重新选择彼此", "责任或利益→误解或偏见→事件加压→站队变化→秘密显露→关系重构", "信任,忠诚,权力平衡,家庭秘密,义务", "每个单元让站队和责任发生可见变化", "家庭责任冲突,偏见后果,秘密压力", "站队改变,责任承担,关系重构", "新秘密,利益选择,关系破裂风险", "只靠吵架;所有人同一声音;关系变化没事件依据", "关系变化是否由具体事件和选择造成"));
        map.put("SUSPENSE_MYSTERY", type("让观众用公平可见的线索不断修正对真相的判断", "谜题→线索→错误解释→新证据→嫌疑变化→真相收缩→揭露", "线索进度,真相暴露,嫌疑状态,知识差,风险", "每个单元排除一种解释并缩小真相", "异常事实,知识差,证据矛盾", "旧线索新解释,嫌疑收缩,事实闭环", "新证据,解释反转,调查风险", "最终靠新设定;线索未提前存在;角色凭空知道答案", "揭露是否由已出现线索推导且知识边界正确"));
        map.put("COMEDY", type("让人物目标在误差与反应链中升级并意外兑现", "目标→误差或身份错位→升级→反应链→意外兑现或反转", "喜剧张力,误会状态,反应链", "每个单元更换误差机制并推进主线", "身份错位,目标冲突,观众信息差", "反应链闭合,意外成功,关系反转", "误会升级,第三方介入,真相将破", "只有段子没有剧情;人物为笑点降智;重复同一梗", "笑点是否推进目标、关系或信息"));
        map.put("GROWTH", type("让观众看到人物通过有代价的选择改变能力和价值观", "缺陷→选择→代价→训练或关系→测试→失败→新选择→能力与价值观变化", "能力,自我信念,关系支持,代价", "每个单元完成一次选择—失败—新选择", "能力缺口,价值冲突,失败风险", "新能力应用,价值选择,关系支持", "更难测试,旧模式复发,选择代价", "升级无代价;只有训练蒙太奇;人物价值观不变化", "成长是否通过可见选择而非旁白宣言"));
        map.put("OTHER", type("兑现项目定义的核心情绪承诺", "目标→阻力→选择→后果→升级→兑现", "目标,风险,关系,知识,资源", "每个单元改变至少一个核心状态", "异常,强目标,未完成动作", "选择后果,信息兑现", "新风险,新决定,新问题", "重复同一冲突且状态不变", "本集是否具有不可删除的叙事变化"));
        return Map.copyOf(map);
    }

    private static ObjectNode type(String promise, String loop, String axes, String unit, String hooks,
                                   String payoffs, String cliffhangers, String forbidden, String qa) {
        ObjectNode value = Documents.obj().put("audiencePromise", promise).put("coreLoop", loop).put("unitPattern", unit).put("typeSpecificQa", qa);
        putList(value, "escalationAxes", axes); putList(value, "hookSources", hooks); putList(value, "payoffSources", payoffs);
        putList(value, "cliffhangerSources", cliffhangers); putList(value, "forbiddenRepetition", forbidden);
        return value;
    }

    private static void putList(ObjectNode target, String field, String value) {
        ArrayNode array = target.putArray(field);
        for (String item : value.split("[,;]")) if (!item.isBlank()) array.add(item.trim());
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
