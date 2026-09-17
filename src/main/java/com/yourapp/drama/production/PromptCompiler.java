package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static com.yourapp.drama.production.ProductionModels.*;
import static com.yourapp.drama.production.ProductionJson.*;

@Service
public class PromptCompiler {
    public static final String IMAGE_COMPILER_VERSION="3.7.0";
    private final ObjectMapper mapper;
    private final ContinuityEngine continuity;
    public PromptCompiler(ObjectMapper mapper, ContinuityEngine continuity) { this.mapper = mapper; this.continuity = continuity; }

    public PromptResult compileImage(JsonNode request) {
        Shot shot = shot(request, mapper);
        ContinuityPlan plan = checkedPlan(request);
        ObjectNode projection=ShotProjectionContext.build(request.path("shot"));
        ObjectNode topology=SpatialTopologyContext.build(request.path("shot"),request.path("assets"));
        String prompt = "连续性约束（优先）：\n" + constraints(shot, plan, request.path("assets"))
            + "\n新镜头：" + shot.shotId() + "；景别 " + shot.shotSize() + "；机位 " + shot.cameraAngle()
            + "；表情 " + shot.emotion() + "。\n"+directorInstructions(request)+"\n摄影机空间参数：" + request.path("shot").path("cameraPlan") + "。\n"
            + "本次只绘制镜头第0秒：" + visibleState(shot, plan) + "。严格按照此时的姿态、抓握接触点和物体状态作画；后续表演由视频阶段执行。\n"
            + "世界到画面的投影：" + projection.path("worldToScreenProjection") + "。先把人物、身体来源方向和固定设施从世界坐标投影到画面坐标，禁止直接猜左右。\n"
            + "持物人体与接触几何（不可镜像）：" + projection + "。可见肢体必须从持物者身体投影所在方向自然连接；指定左/右手、身体站位、朝向、入画方向、抓握接触点必须同时成立，不能只画对道具。\n"
            + "固定空间拓扑合同：" + topology + "。空间投影先于展示完整人物：按机位投影主体世界位置和固定设施，遮挡物的形状、开合状态、开口尺寸保持不变。位于摄影机后方或视锥外的固定设施不得错误出现在当前可见承载面；不同承载面上的设施不得互换、复制或合并。浅景深或背景虚化只允许降低纹理清晰度，不得改变任何固定设施的所属承载面，也不得破坏同面、对立、相邻、前后或内外关系。只画该机位实际可见的身体部分；被遮住的面孔或衣服留在遮挡后，不能为了露脸而扩宽开口、移开遮挡物或搬动人物。\n"
            + "此为 " + shot.relationToPrevious() + " 镜头，按本镜头改变构图、角度及视线，不复制上一戏剧画面的姿态和背景。"
            + "画面只显示角色 " + shot.characterIds() + "；不添加人物、字幕、水印或多余肢体；不重新设计身份。";
        if(request.path("storyFacts").isArray()&&!request.path("storyFacts").isEmpty())prompt += "\n当前可见角色已知的剧情事实（仅限本故事时间）：" + request.path("storyFacts");
        if(request.path("characterStates").isObject()&&!request.path("characterStates").isEmpty())prompt += "\n当前故事时间的人物可变状态（年龄、伤痕、发型、伪装等；身份锚点仍以 Character Bible 为准）：" + request.path("characterStates");
        if(request.path("relationships").isArray()&&!request.path("relationships").isEmpty())prompt += "\n当前故事时间的人物关系（仅限本故事时间）：" + request.path("relationships");
        if(request.path("locationState").isObject()&&request.path("locationState").has("state"))prompt += "\n当前故事时间的地点状态（仅限本故事时间）：" + request.path("locationState");
        List<JsonNode> refs=references(shot, request, plan);
        prompt += "\n参考图片按输入顺序绑定：" + referenceLegend(refs) + "。人物图用于识别脸型、体态和服装，人物朝向与持物姿态由本镜起始状态决定，不照搬定妆站姿。场景布局图只规定门窗、固定设施和光源的世界位置；先在该布局中放置相机，再按本镜机位投影构图，不得为了展示参照物而移动、镜像或重建固定设施。其他场景视角提供建筑外观与材质，道具图提供形制和比例。不同角色不得合并或互换身份。";
        if(!text(request,"revisionFeedback").isBlank())prompt += "\n上一版画面未通过审查，以下是观察到的偏差。依据上述已确认设定修正，不新增人物或改写剧情：\n"+text(request,"revisionFeedback");
        return new PromptResult(IMAGE_COMPILER_VERSION, "SEEDREAM", prompt, refs, plan, null, takes(shot.difficulty()));
    }

    public PromptResult compileVideo(JsonNode request) {
        Shot shot = shot(request, mapper);
        ContinuityPlan plan = checkedPlan(request);
        JsonNode route = routeVideo(request);
        Strategy strategy = Strategy.valueOf(route.path("strategy").asText());
        double actionStart = shot.duration() * 0.2, actionEnd = shot.duration() * 0.8;
        String prompt = "总时长 " + number(shot.duration()) + " 秒。\n连续性约束：\n" + constraints(shot, plan, request.path("assets"))
            + "\n"+directorInstructions(request)+"\n"
            + "\n0.0-" + number(actionStart) + " 秒：保持起始姿态和道具状态，身体稳定。\n"
            + number(actionStart) + "-" + number(actionEnd) + " 秒：只完成一次主要动作「" + shot.action() + "」。\n"
            + number(actionEnd) + "-" + number(shot.duration()) + " 秒：动作自然收尾，保持已到达姿态，为剪辑留出稳定尾段。\n"
            + "摄影机：" + shot.shotSize() + "，" + shot.cameraAngle() + "，机位位置、相机高度、距离、镜头焦段、俯仰角、水平朝向、主体位置、对焦点、景深、光线方向：" + request.path("shot").path("cameraPlan") + "；唯一主要运镜 " + shot.cameraMovement() + "。\n"
            + "表演情绪：" + shot.emotion() + "。保持场景布局、空间关系、时间光照及指定道具。"
            + "禁止换脸、换服装、增加人物、多余肢体、动作重复、突然瞬移、无关剧情或额外运镜。";
        List<JsonNode> refs = new ArrayList<>(references(shot, request, plan));
        route.path("references").forEach(refs::add);
        return new PromptResult("2.0.0", "SEEDANCE", prompt, List.copyOf(refs), plan, strategy, takes(shot.difficulty()));
    }

    public JsonNode routeVideo(JsonNode request) {
        Shot shot = shot(request, mapper);
        ObjectNode result = mapper.createObjectNode();
        var references = result.putArray("references");
        Strategy strategy;
        String motion = text(request, "motionReferenceUrl");
        JsonNode previousTake = request.path("previousTake");
        if (!motion.isBlank()) {
            throw new IllegalArgumentException("当前视频流程使用已批准关键帧；动作参考需要独立的多模态路由，不能与首帧模式混合提交");
        } else if (shot.relationToPrevious() == ShotRelation.CONTINUOUS) {
            strategy = Strategy.CONTINUATION;
            if (!previousTake.path("locked").asBoolean(false) || !previousTake.path("qcPassed").asBoolean(false))
                throw new IllegalArgumentException("CONTINUOUS 必须提供已通过 QC 并锁定的 previousTake；不能自动退回切镜");
            // Continuity is checked against the approved previous end state. The current
            // approved image remains the sole first frame; do not mix Ark's two routes.
            if(!previousTake.path("observedState").isObject())throw new IllegalArgumentException("连续镜头缺少上一已批准视频的实际结束状态");
        } else strategy = Strategy.INDEPENDENT_CUT;

        JsonNode keyframe = request.path("keyframe");
        if (!keyframe.path("locked").asBoolean(false)) throw new IllegalArgumentException("视频需要已锁定的正式 Keyframe");
        String providerUrl = text(keyframe, "providerUrl");
        if (providerUrl.isBlank()) throw new IllegalArgumentException("缺少 Seedream 原始 provider_url；archive_url 不能替代");
        String expiry = text(keyframe, "providerUrlExpiresAt");
        if ("EXPIRED".equals(text(keyframe, "handoffStatus"))
            || (!expiry.isBlank() && !Instant.parse(expiry).isAfter(Instant.now())))
            throw new IllegalArgumentException("PROVIDER_URL_EXPIRED：使用同一 Prompt/引用/参数重新生成 Keyframe 并复核，不得替换为归档 URL");
        references.add(reference("KEYFRAME_PROVIDER", text(keyframe, "id"), validUrl(providerUrl)));
        return result.put("strategy", strategy.name()).put("recommendedTakes", takes(shot.difficulty()));
    }

    private ContinuityPlan checkedPlan(JsonNode request) {
        ContinuityPlan plan = continuity.plan(request);
        if (!plan.passed()) throw new IllegalArgumentException("连续性检查未通过: " + plan.risks());
        return plan;
    }
    private String directorInstructions(JsonNode request){JsonNode shot=request.path("shot");StringBuilder value=new StringBuilder("导演方案：意图 ")
        .append(text(shot,"directorIntent")).append("；镜头存在理由 ").append(text(shot,"shotPurpose")).append("；主体 ").append(text(shot,"subject"))
        .append("；次要主体 ").append(shot.path("secondarySubjects")).append("；对焦 ").append(text(shot,"focus")).append("；视线 ").append(text(shot,"eyeLine"))
        .append("；对白说话者 ").append(text(shot,"dialogueOwner")).append("。\n空间调度：").append(shot.path("blocking"))
        .append("。\n表演方案：").append(shot.path("performancePlan")).append("；可见性方案：").append(shot.path("visibilityPlan"));
        JsonNode beat=shot.path("dramaticBeatSnapshot");if(beat.isObject())value.append("。\n本节拍情绪从 ").append(text(beat,"emotionBefore")).append(" 推进到 ").append(text(beat,"emotionAfter")).append("；信息揭示：").append(text(beat,"informationReveal"));
        return value.toString();}
    private String constraints(Shot shot, ContinuityPlan plan, JsonNode assets) {
        ObjectNode characters=mapper.createObjectNode(),props=mapper.createObjectNode();
        plan.inheritedConstraints().path("characters").fields().forEachRemaining(entry->{
            ObjectNode character=visualCanon(entry.getValue());
            character.set("wardrobe",visualCanon(entry.getValue().path("wardrobe")));
            characters.set(entry.getKey(),character);
        });
        plan.inheritedConstraints().path("props").fields().forEachRemaining(entry->props.set(entry.getKey(),visualCanon(entry.getValue())));
        return "仅使用可见角色各自身份锚点；身份、年龄、脸型、核心五官比例不可改变。\n"
            + "Character/Look Bible: " + characters
            + "\nLocation Bible: " + visualCanon(plan.inheritedConstraints().path("location"))
            + "\nProp Bible: " + props
            + "\nScene State: " + visibleState(shot, plan) + "\nStyle Bible: " + assets.path("style").asText("写实电影质感");
    }
    /** A frame depicts its visible cast at t=0, not the accumulated off-screen cast or future action. */
    private ObjectNode visibleState(Shot shot, ContinuityPlan plan) {
        ObjectNode state = plan.startState().deepCopy();
        if (state.path("characters").isObject()) ((ObjectNode) state.path("characters")).retain(shot.characterIds());
        if (state.path("props").isObject()) ((ObjectNode) state.path("props")).retain(shot.propIds());
        return state;
    }
    /** Keep visual canon in the prompt; media URLs and persistence metadata travel separately. */
    private ObjectNode visualCanon(JsonNode asset) {
        ObjectNode result=mapper.createObjectNode();
        for(String field:List.of("id","identityId","lookId","characterId","name","description","identityTraits","locationBible","propBible","state"))
            if(asset.has(field))result.set(field,asset.path(field));
        return result;
    }
    private List<JsonNode> references(Shot shot, JsonNode request, ContinuityPlan plan) {
        List<JsonNode> characters=new ArrayList<>(),locations=new ArrayList<>(),props=new ArrayList<>(),refs=new ArrayList<>();
        JsonNode assets=request.path("assets"),selections=request.path("shot").path("referenceViews");
        String locationView=selections.path(shot.locationId()).asText();
        if(!LocationViewProjection.isPerspective(locationView))throw new IllegalArgumentException("LAYOUT 俯视图只能作为空间辅助，不能作为透视镜头的主场景参考；请选择 "+String.join("、",LocationViewProjection.perspectiveViews()));
        for (String id : shot.characterIds()) {
            JsonNode character = findAsset(assets, "characters", id);
            String lookId=required(plan.startState().path("characters").path(id),"lookId");
            JsonNode look=findAsset(assets,"looks",lookId);
            ObjectNode ref=selectedReference("CHARACTER_LOOK",look,selections.path(lookId).asText(),text(character,"name")+" / "+text(look,"name"));ref.put("characterId",id);characters.add(ref);
        }
        JsonNode location = findAsset(assets, "locations", shot.locationId());
        locations.add(selectedReference("LOCATION",location,locationView,text(location,"name")));
        locations.add(selectedReference("LOCATION_LAYOUT",location,"LAYOUT",text(location,"name")+" / 固定空间布局（不直接入画）"));
        for(String id:shot.propIds()){JsonNode prop=findAsset(assets,"props",id);props.add(selectedReference("PROP",prop,selections.path(id).asText(),text(prop,"name")));}
        String requiredDetail=text(request.path("shot").path("visibilityPlan"),"requiredDetail");
        if("PROP_DETAIL".equals(requiredDetail)){refs.addAll(props);refs.addAll(locations);refs.addAll(characters);}
        else if("ESTABLISH_SPACE".equals(text(request.path("shot"),"directorIntent"))){refs.addAll(locations);refs.addAll(characters);refs.addAll(props);}
        else{refs.addAll(characters);refs.addAll(locations);refs.addAll(props);}
        return List.copyOf(refs);
    }
    private ObjectNode selectedReference(String role,JsonNode asset,String view,String name){
        if(view.isBlank())throw new IllegalArgumentException("分镜没有指定素材视角："+name);
        for(JsonNode item:asset.path("approvedViews"))if(view.equals(text(item,"view"))&&item.path("approved").asBoolean()&&!item.path("stale").asBoolean()){
            ObjectNode ref=reference(role,required(asset,"id"),required(item,"providerUrl"));ref.put("viewId",required(item,"id")).put("view",view).put("setVersion",item.path("setVersion").asInt()).put("name",name);return ref;
        }
        throw new IllegalArgumentException("缺少已批准的素材视角："+name+" / "+view);
    }
    private String referenceLegend(List<JsonNode> refs){List<String> entries=new ArrayList<>();for(int i=0;i<refs.size();i++){JsonNode r=refs.get(i);String authority=switch(text(r,"role")){case "LOCATION"->"当前机位透视主参考";case "LOCATION_LAYOUT"->"俯视空间辅助，仅用于世界坐标定位，不可照抄为当前画面";case "CHARACTER_LOOK"->"人物身份与定妆参考";case "PROP"->"道具形制参考";default->"制作参考";};entries.add("图"+(i+1)+"="+text(r,"name")+"，"+text(r,"view")+"，"+authority+"，版本"+r.path("setVersion").asInt());}return String.join("；",entries);}
    private ObjectNode reference(String role, String id, String url) { return mapper.createObjectNode().put("role", role).put("assetId", id).put("url", url); }
    private String validUrl(String value) {
        URI uri = URI.create(value);
        String scheme=uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("媒体引用必须是有效 HTTP(S) URL");
        return value;
    }
    private int takes(Difficulty difficulty) { return switch (difficulty) { case A -> 1; case B, C -> 2; case D -> 3; }; }
    private String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
