package com.yourapp.drama.provider.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.yourapp.drama.workflow.Documents.*;

/** Deterministic fixture for the same review gates used by live mode. Never used by a live adapter. */
final class StoryDevelopmentDemo {
    static ObjectNode generate(JsonNode input){
        JsonNode project=input.path("project");double seconds=project.path("targetDuration").asDouble(20);
        if("CORE".equals(text(input,"phase"))){
            ObjectNode core=obj().put("title",project.path("name").asText("演示故事")).put("logline",project.path("idea").asText("主角追查一条线索"))
                .put("worldRules","本地演示：事件遵守同一时间线，秘密只由在场者获知").put("seasonArc","主角发现线索，付出代价，最终通过选择揭开真相")
                .put("characterArcs","主角从独自追查到主动向伙伴求助").put("foreshadowingRules","旧钥匙在开场出现，终局打开与线索相关的柜门").put("continuityRules","主角全程蓝布外套；换装、受伤与钥匙移交须写出原因");
            ObjectNode person=obj().put("characterKey","lead").put("name","周伯").put("description","一位珍惜邻里情分、习惯独自承担问题的老人");person.set("identityTraits",obj().put("age","六十五岁").put("face","方脸，左眉有一道短疤").put("hair","灰白短发").put("body","偏瘦，身高一米七").put("voiceDialect",project.path("dialect").asText("MANDARIN")));person.putArray("looks").add(obj().put("lookKey","workwear").put("name","田间装").put("description","靛蓝棉布外套、深灰长裤、黑布鞋，袖口磨白"));core.putArray("characters").add(person);
            ObjectNode loc=obj().put("locationKey","yard").put("name","村口院子").put("description","石墙围合的院子，南门通向土路");loc.set("locationBible",obj().put("layout","南门、北侧屋檐、东侧水井").put("spatialAnchors","门与井相隔三米").put("lighting","西侧落日照亮院子"));core.putArray("locations").add(loc);
            ObjectNode prop=obj().put("propKey","key").put("name","旧钥匙").put("description","带圆环的锈铁钥匙").put("state","周伯持有，完好");prop.set("propBible",obj().put("appearance","铁灰色、齿部有两道缺口").put("scale","长六厘米").put("ownership","周伯右侧衣袋"));core.putArray("props").add(prop);return core;
        }
        if("OUTLINE_BATCH".equals(text(input,"phase"))){
            ObjectNode out=obj();var cards=out.putArray("episodes");String start=input.path("sourceSnapshot").path("requiredStartState").asText("黄昏，周伯穿田间装站在院内，钥匙在右侧衣袋");
            for(int n=input.path("startEpisode").asInt();n<=input.path("endEpisode").asInt();n++){String end="第 "+n+" 集结束：周伯仍穿田间装站在院内，持有旧钥匙，并获得第 "+n+" 条线索";ObjectNode card=obj().put("episodeNo",n).put("title","第 "+n+" 集 · 线索").put("summary","周伯追查线索，在阻碍中作出选择").put("startState",start).put("endState",end);refs(card);card.putArray("scenePlan").add(scene(seconds));cards.add(card);start=end;}return out;
        }
        JsonNode outline=input.path("sourceSnapshot").path("episodeOutline");ObjectNode out=obj().put("title",text(outline,"title")).put("summary",text(outline,"summary")).put("startState",text(outline,"startState")).put("endState",text(outline,"endState"));
        out.put("script","【本地演示剧本】黄昏，村口院子，周伯穿着靛蓝棉布外套，右手按住口袋里的旧钥匙。他在南门停步，听见北侧屋檐下传来敲击声，先回头确认身后没有人，再慢慢走向柜门。周伯：这把钥匙留了这么久，今天总要试一试。他举起钥匙，却在锁孔边发现一道新鲜划痕，手停在半空，决定先查清谁刚刚来过。");refs(out);out.putArray("scenes").add(scene(seconds));return out;
    }
    private static void refs(ObjectNode n){n.putArray("characterKeys").add("lead");n.putArray("locationKeys").add("yard");n.putArray("propKeys").add("key");}
    private static ObjectNode scene(double seconds){return obj().put("name","院内追查").put("description","周伯穿田间装，带旧钥匙从南门走到北侧屋檐，发现划痕并停步观察。").put("duration",seconds);}
}
