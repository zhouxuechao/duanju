package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import static com.yourapp.drama.production.ProductionJson.*;
import static com.yourapp.drama.production.ProductionModels.*;

/** Provider-neutral, task-specific Seedream/Seedance prompt compiler. */
@Service
public class PromptCompiler {
    public static final String IMAGE_COMPILER_VERSION="4.0.0";
    public static final String VIDEO_COMPILER_VERSION="4.0.0-sequence";
    private final ObjectMapper mapper;
    private final ContinuityEngine continuity;
    private final ProviderCapabilityRegistry capabilities;
    private final PromptBudgeter budgeter=new PromptBudgeter();
    private final SequenceBoundaryGate boundary=new SequenceBoundaryGate();

    @Autowired
    public PromptCompiler(ObjectMapper mapper,ContinuityEngine continuity,ProviderCapabilityRegistry capabilities){this.mapper=mapper;this.continuity=continuity;this.capabilities=capabilities;}
    public PromptCompiler(ObjectMapper mapper,ContinuityEngine continuity){this(mapper,continuity,new ProviderCapabilityRegistry());}

    public PromptResult compileImage(JsonNode request){
        Shot shot=shot(request,mapper);ContinuityPlan plan=checkedPlan(request);String task=text(request,"imageTaskType");if(task.isBlank())task="KEYFRAME";
        if(!Set.of("ASSET_REFERENCE","STORYBOARD","KEYFRAME","REPAIR_EDIT").contains(task))throw new IllegalArgumentException("IMAGE_TASK_TYPE_INVALID："+task);
        List<JsonNode> refs=references(shot,request,plan);JsonNode provider=request.path("providerCapabilities").isObject()?request.path("providerCapabilities"):capabilities.image();
        if(refs.size()>provider.path("maxImageRefs").asInt(10))throw new IllegalArgumentException("PROVIDER_CAPABILITY_EXCEEDED：图片参考数量超过当前适配器能力");
        ObjectNode projection=ShotProjectionContext.build(request.path("shot"));ObjectNode topology=SpatialTopologyContext.build(request.path("shot"),request.path("assets"));ObjectNode visible=visibleState(shot,plan);
        String taskPurpose=switch(task){case "STORYBOARD"->"视觉构图预演，只决定机位、站位、姿态和空间关系，不重设计人物与场景";case "REPAIR_EDIT"->"局部修复已存在关键帧，只改变指定错误，其余已正确维度全部保持";case "ASSET_REFERENCE"->"标准资产参考，不承载剧情表演";default->"正式关键帧，只绘制镜头第0秒的可见状态，后续动作留给视频阶段";};
        List<PromptBudgeter.Section> sections=new ArrayList<>();
        sections.add(section("TASK / IMAGE PURPOSE",task+"。"+taskPurpose,100,true));
        sections.add(section("REFERENCE BINDINGS",referenceLegend(refs)+"。不同角色不得合并或互换身份；每个参考只控制声明的维度。",100,true));
        sections.add(section("CHARACTER IDENTITY LOCK",identityLocks(shot,request,plan),100,true));
        sections.add(section("CURRENT LOOK / VISIBLE STATE","本次只绘制镜头第0秒："+visible+"。年龄、脸型、五官比例、发型、伤痕、当前定妆和伪装以当前状态及批准定妆为准。当前镜头必要叙事上下文："+narrativeContext(shot,request),98,true));
        sections.add(section("LOCATION IDENTITY / LAYOUT LOCK","Location Bible: "+visualCanon(plan.inheritedConstraints().path("location"))+"\n固定空间拓扑合同："+topology+"。位于摄影机后方或视锥外的固定设施不得错误出现在当前可见承载面；不同承载面上的设施不得互换、复制或合并。浅景深或背景虚化只允许降低纹理清晰度，不得改变任何固定设施的所属承载面。门窗、开口尺寸保持不变。",98,true));
        sections.add(section("PROP IDENTITY / HOLDER",propLocks(shot,request,plan)+"\n持物人体与接触几何（不可镜像）："+projection+"。可见肢体必须从持物者身体投影所在方向自然连接；手位、身体站位、朝向与抓握接触点必须同时成立。",98,true));
        sections.add(section("ACTION / POSE / EYELINE",imagePose(task,shot,request)+"\n世界到画面的投影："+projection.path("worldToScreenProjection"),90,true));
        sections.add(section("COMPOSITION / BLOCKING",directorInstructions(request)+"\n此为 "+shot.relationToPrevious()+" 镜头，按导演方案改变构图，不能复制上一画面的姿态。",88,true));
        sections.add(section("CAMERA","景别 "+shot.shotSize()+"；角度 "+shot.cameraAngle()+"；机位完整参数 "+request.path("shot").path("cameraPlan"),86,true));
        sections.add(section("LIGHTING",physicalLighting(request),70,false));
        sections.add(section("VISUAL STYLE",request.path("assets").path("style").asText(request.path("style").asText("写实")),55,false));
        sections.add(section("CONTINUITY LOCK",constraints(shot,plan,request.path("assets")),94,true));
        sections.add(section("DYNAMIC AVOID",String.join("；",dynamicAvoid(shot,request,true)),82,true));
        sections.add(section("OUTPUT",outputProfile(request,"静态单帧，不含字幕、水印或额外人物"),80,true));
        if(!text(request,"revisionFeedback").isBlank())sections.add(section("REPAIR TARGET","上一版偏差："+text(request,"revisionFeedback")+"。只修正该偏差，不改变未被点名的身份、场景、构图和道具。",96,true));
        String prompt=budgeter.compile(sections,12_000);
        return new PromptResult(IMAGE_COMPILER_VERSION,"SEEDREAM",prompt,refs,plan,null,takes(shot.difficulty()));
    }

    public PromptResult compileVideo(JsonNode request){
        Shot shot=shot(request,mapper);ContinuityPlan plan=checkedPlan(request);JsonNode route=routeVideo(request);Strategy strategy=Strategy.valueOf(route.path("strategy").asText());
        List<JsonNode> refs=new ArrayList<>(references(shot,request,plan));route.path("references").forEach(refs::add);
        List<String> carriers=promptCarriers(request.path("shot"));boundary.validate(request,refs,carriers);
        List<PromptBudgeter.Section> sections=new ArrayList<>();
        sections.add(section("SHOT INTENT / CARRIERS","shotPurpose="+shot.purpose()+"\nfeltIntent="+text(request.path("shot"),"feltIntent")+"\nvisible carriers="+carriers,100,true));
        sections.add(section("REFERENCE AUTHORITY",referenceAuthority(refs),100,true));
        sections.add(section("SOURCE / LINEAGE",sourceLineage(request,strategy),99,true));
        sections.add(section("ACTUAL OPENING STATE",openingState(request,plan,shot),99,true));
        sections.add(section("CURRENT ACTION / ENDPOINT","只完成当前动作："+shot.action()+"\nendpoint="+endpoint(request.path("shot"))+"。不扩写下一镜，不提前完成未来剧情。",99,true));
        sections.add(section("TIMED BEATS",timedBeats(shot,request.path("shot")),94,true));
        sections.add(section("CAMERA / MOTION PHASE",cameraContract(shot,request.path("shot")),90,true));
        sections.add(section("PHYSICS / INTERACTION",physicsContract(shot,request,plan),92,true));
        sections.add(section("TIMED STATE TRANSITIONS",stateTransitions(shot,request.path("shot")),93,true));
        sections.add(section("HIGH-RISK CONTINUITY LOCKS",constraints(shot,plan,request.path("assets")),98,true));
        sections.add(section("COMPLETED BEAT EXCLUSIONS",listOrNone(request.path("shot").path("completedBeats"),"不得重复已经发生的动作或信息"),91,true));
        sections.add(section("RESERVED FUTURE BEAT EXCLUSIONS",listOrNone(request.path("shot").path("reservedFutureBeats"),"本镜不得提前发生、暗示或泄露"),91,true));
        sections.add(section("DYNAMIC AVOID",String.join("；",dynamicAvoid(shot,request,false)),89,true));
        sections.add(section("AUDIO POLICY","generate_audio=false。视频只负责可见表演、呼吸与开口时机；对白、环境声、拟音和音乐由 TTS/LipSync/PostProduction 统一完成。",88,true));
        sections.add(section("OUTPUT CONSTRAINTS",outputProfile(request,"一个 Shot 对应一个连续 Video Take；不得在片内剪成多镜头；结尾稳定可衔接"),87,true));
        if(request.path("retake").isObject())sections.add(section("ONE-VARIABLE RETAKE","issueCode="+text(request.path("retake"),"issueCode")+"；repairStrategy="+text(request.path("retake"),"repairStrategy")+"；只修改 sections="+request.path("retake").path("changedPromptSections")+"，其余已正确维度保持。",97,true));
        String prompt=budgeter.compile(sections,10_000);
        for(String required:List.of("[REFERENCE AUTHORITY]","[CURRENT ACTION / ENDPOINT]","[HIGH-RISK CONTINUITY LOCKS]"))if(!prompt.contains(required))throw new IllegalArgumentException("PROMPT_BUDGET_LOST_REQUIRED_SECTION："+required);
        return new PromptResult(VIDEO_COMPILER_VERSION,"SEEDANCE",prompt,List.copyOf(refs),plan,strategy,takes(shot.difficulty()));
    }

    public JsonNode routeVideo(JsonNode request){
        Shot shot=shot(request,mapper);ObjectNode result=mapper.createObjectNode();ArrayNode refs=result.putArray("references");Strategy strategy;
        String motion=text(request,"motionReferenceUrl");JsonNode previous=request.path("previousTake");
        if(!motion.isBlank())throw new IllegalArgumentException("当前首帧生产路线不能与动作参考路线混合提交");
        if(shot.relationToPrevious()==ShotRelation.CONTINUOUS){
            if(!previous.path("locked").asBoolean()||!previous.path("selected").asBoolean()||!previous.path("qcPassed").asBoolean())throw new IllegalArgumentException("CONTINUOUS 必须提供已选择、通过 QC 并锁定的 previousTake；不能自动退回切镜");
            if(!previous.path("observedState").isObject()||previous.path("observedState").isEmpty())throw new IllegalArgumentException("连续镜头缺少上一已批准视频的实际结束状态");
            int depth=previous.path("continuationDepth").asInt(),max=request.path("sceneContinuityPolicy").path("maxContinuationDepth").asInt(2);
            strategy=depth>=max&&request.path("reanchorPlan").isObject()?Strategy.REANCHOR_AFTER_DRIFT:Strategy.CONTINUATION;
            if(previous.hasNonNull("videoUrl"))refs.add(referenceBinding(ReferenceBinding.Role.PREVIOUS_TAKE.name(),text(previous,"id"),text(previous,"videoUrl"),text(previous,"id"),previous.path("takeNo").asInt(1),"上一条已接受视频只控制瞬时姿态、位置和运动相位，不控制长期身份",List.of("openingPose","position","motionPhase"),List.of("characterIdentity","wardrobe","locationIdentity","propIdentity"),85));
        }else strategy=Strategy.INDEPENDENT_CUT;
        JsonNode keyframe=request.path("keyframe");if(!keyframe.path("locked").asBoolean())throw new IllegalArgumentException("视频需要已锁定的正式 Keyframe");
        String url=text(keyframe,"providerUrl");if(url.isBlank())throw new IllegalArgumentException("缺少 Seedream 原始 provider_url；archive_url 不能替代");
        String expiry=text(keyframe,"providerUrlExpiresAt");if("EXPIRED".equals(text(keyframe,"handoffStatus"))||(!expiry.isBlank()&&!Instant.parse(expiry).isAfter(Instant.now())))throw new IllegalArgumentException("PROVIDER_URL_EXPIRED：使用同一 Prompt/引用/参数重新生成 Keyframe 并复核，不得替换为归档 URL");
        refs.add(referenceBinding(ReferenceBinding.Role.KEYFRAME_PROVIDER.name(),text(keyframe,"id"),validUrl(url),text(keyframe,"id"),keyframe.path("version").asInt(1),"正式首帧控制本镜起始构图、姿态和画面空间",List.of("openingComposition","openingPose"),List.of("futureAction","futureState"),95));
        return result.put("strategy",strategy.name()).put("recommendedTakes",takes(shot.difficulty()));
    }

    private PromptBudgeter.Section section(String name,String value,int priority,boolean required){return new PromptBudgeter.Section(name,value,priority,required);}
    private ContinuityPlan checkedPlan(JsonNode request){ContinuityPlan plan=continuity.plan(request);if(!plan.passed())throw new IllegalArgumentException("连续性检查未通过: "+plan.risks());return plan;}
    private String identityLocks(Shot shot,JsonNode request,ContinuityPlan plan){StringBuilder out=new StringBuilder();for(String id:shot.characterIds()){JsonNode actor=findAsset(request.path("assets"),"characters",id),state=plan.startState().path("characters").path(id);String lookId=text(state,"lookId");JsonNode look=findAsset(request.path("assets"),"looks",lookId);out.append(id).append(": identity source=approved CHARACTER_LOOK ").append(lookId).append("；preserve face/feature proportions/apparent age/body/distinctive features；current look=").append(visualCanon(look)).append("；identity=").append(visualCanon(actor)).append('\n');}return out.toString();}
    private String propLocks(Shot shot,JsonNode request,ContinuityPlan plan){StringBuilder out=new StringBuilder();for(String id:shot.propIds()){JsonNode prop=findAsset(request.path("assets"),"props",id),state=plan.startState().path("props").path(id);out.append(id).append(": canon=").append(visualCanon(prop)).append("；holder=").append(text(state,"holder")).append("；hand=").append(text(state,"heldByHand")).append("；position=").append(text(state,"position")).append("；state=").append(text(state,"state")).append('\n');}return out.toString();}
    private String imagePose(String task,Shot shot,JsonNode request){String value=directorInstructions(request)+"\n姿态、视线与接触点来自 blocking/performance/startState。";if("STORYBOARD".equals(task))value+=" 构图预演可表达当前动作意图："+shot.action();return value;}
    private String physicalLighting(JsonNode request){JsonNode camera=request.path("shot").path("cameraPlan"),state=request.path("shot").path("startState");return "physical source/direction="+text(camera,"lightingDirection")+"；scene lighting="+text(state,"lighting")+"；保持色温关系、阴影方向和实际光源位置，不用空泛氛围词替代。";}
    private String outputProfile(JsonNode request,String rule){JsonNode profile=request.path("outputProfile").isObject()?request.path("outputProfile"):request.path("videoOutputProfile");return rule+"；profile="+profile+"；providerCapabilitiesVersion="+request.path("providerCapabilities").path("version").asText(ProviderCapabilityRegistry.VERSION);}
    private String sourceLineage(JsonNode request,Strategy strategy){JsonNode previous=request.path("previousTake");return "sequenceRelation="+text(request.path("shot"),"sequenceRelation")+"；strategy="+strategy+"；canonical source=approved Character/Look/Location/Prop；continuity source="+(previous.isObject()?"accepted take "+text(previous,"id")+" depth="+previous.path("continuationDepth").asInt():"current locked keyframe")+"。上一视频的轻微漂移不得晋升为 canonical identity。";}
    private String openingState(JsonNode request,ContinuityPlan plan,Shot shot){JsonNode observed=request.path("previousTake").path("observedState"),source=observed.isObject()&&!observed.isEmpty()?observed:visibleState(shot,plan);return visibleSequenceState(source,shot)+"。Source carries state; text only carries the current delta. 不重新描述全部历史。";}
    private String endpoint(JsonNode shot){String explicit=text(shot,"endpoint");return explicit.isBlank()?shot.path("endState").toString():explicit;}
    private String timedBeats(Shot shot,JsonNode raw){if(raw.path("timedBeats").isArray()&&!raw.path("timedBeats").isEmpty())return raw.path("timedBeats").toString();double a=Math.max(.4,shot.duration()*.2),b=Math.max(a+.4,shot.duration()*.75);return "0.0–"+number(a)+"s 保持实际起始姿态；"+number(a)+"–"+number(b)+"s 完成一次主要动作「"+shot.action()+"」；"+number(b)+"–"+number(shot.duration())+"s 到达 endpoint 并稳定停留。";}
    private String cameraContract(Shot shot,JsonNode raw){JsonNode camera=raw.path("cameraPlan");return "shotScale="+shot.shotSize()+"；angle="+shot.cameraAngle()+"；唯一主运镜="+shot.cameraMovement()+"；speed="+text(camera,"movementSpeed")+"；path="+text(camera,"movementPath")+"；subjectRelationship="+text(camera,"subjectPlacement")+"；endpoint="+text(camera,"focusPoint")+"。不得叠加第二套主运镜；CONTINUOUS 不得无授权越轴或反转屏幕方向。";}
    private String physicsContract(Shot shot,JsonNode request,ContinuityPlan plan){JsonNode performance=request.path("shot").path("performancePlan"),state=visibleState(shot,plan);return "actor→action→contact→consequence→endpoint："+performance+"；start contact state="+state.path("props")+"。手、道具、门、水、布料和身体接触遵守连续物理；道具只能在显式接触与转移后改变持有人或手位。";}
    private String stateTransitions(Shot shot,JsonNode raw){JsonNode changes=raw.path("stateChanges").isArray()?raw.path("stateChanges"):raw.path("authorizedChanges");if(!changes.isArray()||changes.isEmpty())return "本镜无授权状态变化；所有服装、伤痕、湿度、破损、门窗和持物保持到 endpoint。";StringBuilder out=new StringBuilder();for(JsonNode change:changes){double at=change.path("atSeconds").asDouble(shot.duration()*.6);out.append("before ").append(number(at)).append("s keep ").append(text(change,"path")).append('=').append(text(change,"from")).append("；at ").append(number(at)).append("s because ").append(text(change,"reason")).append(" → ").append(text(change,"to")).append("；forbidden before trigger=").append(text(change,"to")).append('\n');}return out.toString();}
    private String listOrNone(JsonNode values,String rule){return rule+"："+(values.isArray()&&!values.isEmpty()?values:"[]");}
    private List<String> promptCarriers(JsonNode shot){List<String> values=new ArrayList<>();collectStrings(shot.path("promptCarriers"),values);if(values.isEmpty()){collectStrings(shot.path("performancePlan"),values);collectStrings(shot.path("cameraPlan"),values);String lighting=text(shot.path("cameraPlan"),"lightingDirection");if(!lighting.isBlank())values.add("lighting="+lighting);}return values.stream().filter(v->!v.isBlank()).distinct().limit(12).toList();}
    private void collectStrings(JsonNode node,List<String> out){if(node.isTextual()){String value=node.asText().trim();if(!value.isBlank())out.add(value);}else if(node.isArray())node.forEach(v->collectStrings(v,out));else if(node.isObject())node.fields().forEachRemaining(e->collectStrings(e.getValue(),out));}
    private List<String> dynamicAvoid(Shot shot,JsonNode request,boolean image){LinkedHashSet<String> avoid=new LinkedHashSet<>(List.of("identity drift","wardrobe redesign","extra or missing characters","duplicate limbs","unplanned camera move"));JsonNode state=shot.startState();if(shot.characterIds().size()>1)avoid.add("identity swap between characters");for(String prop:shot.propIds()){JsonNode p=state.path("props").path(prop);String hand=text(p,"heldByHand");if(!hand.isBlank()){avoid.add("wrong hand for "+prop);avoid.add(prop+" detached from the specified grip");}avoid.add("missing or duplicated "+prop);}if(request.path("shot").path("blocking").isObject()){avoid.add("mirrored screen direction");avoid.add("body entering from the wrong side");}if(image){avoid.add("moving doors/windows/fixed facilities to another wall");avoid.add("background topology drift");}else{avoid.add("repeating completed action");avoid.add("performing reserved future beat");avoid.add("state change before its trigger");}return avoid.stream().limit(18).toList();}
    private String referenceAuthority(List<JsonNode> refs){ArrayNode compact=mapper.createArrayNode();for(JsonNode ref:refs){ObjectNode item=mapper.createObjectNode();for(String field:List.of("referenceId","role","subjectId","sourceResourceId","sourceVersion","controls","mustNotTransfer","authorityPriority","instructions"))if(ref.has(field))item.set(field,ref.path(field).deepCopy());compact.add(item);}return compact.toString();}
    private String directorInstructions(JsonNode request){JsonNode shot=request.path("shot");StringBuilder value=new StringBuilder("导演方案：意图 ").append(text(shot,"directorIntent")).append("；镜头存在理由 ").append(text(shot,"shotPurpose")).append("；主体 ").append(text(shot,"subject")).append("；次要主体 ").append(shot.path("secondarySubjects")).append("；对焦 ").append(text(shot,"focus")).append("；视线 ").append(text(shot,"eyeLine")).append("；对白说话者 ").append(text(shot,"dialogueOwner")).append("。\n空间调度：").append(shot.path("blocking")).append("。\n表演方案：").append(shot.path("performancePlan")).append("；可见性方案：").append(shot.path("visibilityPlan"));JsonNode beat=shot.path("dramaticBeatSnapshot");if(beat.isObject())value.append("。\n本节拍情绪从 ").append(text(beat,"emotionBefore")).append(" 推进到 ").append(text(beat,"emotionAfter")).append("；信息揭示：").append(text(beat,"informationReveal"));return value.toString();}
    private String constraints(Shot shot,ContinuityPlan plan,JsonNode assets){ObjectNode characters=mapper.createObjectNode(),props=mapper.createObjectNode();plan.inheritedConstraints().path("characters").fields().forEachRemaining(entry->{ObjectNode character=visualCanon(entry.getValue());character.set("wardrobe",visualCanon(entry.getValue().path("wardrobe")));characters.set(entry.getKey(),character);});plan.inheritedConstraints().path("props").fields().forEachRemaining(entry->props.set(entry.getKey(),visualCanon(entry.getValue())));return "不可丢失的固定视觉设定。Character/Look Bible="+characters+"；Location Bible="+visualCanon(plan.inheritedConstraints().path("location"))+"；Prop Bible="+props+"；Scene State="+visibleState(shot,plan)+"；Style Bible="+assets.path("style").asText("写实");}
    private ObjectNode visibleState(Shot shot,ContinuityPlan plan){ObjectNode state=plan.startState().deepCopy();if(state.path("characters").isObject())((ObjectNode)state.path("characters")).retain(shot.characterIds());if(state.path("props").isObject())((ObjectNode)state.path("props")).retain(shot.propIds());return state;}
    private ObjectNode visibleSequenceState(JsonNode source,Shot shot){ObjectNode state=mapper.createObjectNode();for(String field:List.of("locationId","time","lighting","spatialRelations"))if(source.has(field))state.set(field,source.path(field).deepCopy());ObjectNode characters=state.putObject("characters"),props=state.putObject("props");for(String id:shot.characterIds())if(source.path("characters").has(id))characters.set(id,source.path("characters").path(id).deepCopy());for(String id:shot.propIds())if(source.path("props").has(id))props.set(id,source.path("props").path(id).deepCopy());return state;}
    private ObjectNode narrativeContext(Shot shot,JsonNode request){ObjectNode result=mapper.createObjectNode(),characters=result.putObject("characterStates");for(String id:shot.characterIds())if(request.path("characterStates").has(id))characters.set(id,request.path("characterStates").path(id).deepCopy());ArrayNode facts=result.putArray("storyFacts");int count=0;for(JsonNode fact:request.path("storyFacts"))if(count++<12)facts.add(fact.deepCopy());ArrayNode relationships=result.putArray("relationships");count=0;for(JsonNode relationship:request.path("relationships")){String from=text(relationship,"fromCharacterId"),to=text(relationship,"toCharacterId");if((shot.characterIds().contains(from)||shot.characterIds().contains(to))&&count++<12)relationships.add(relationship.deepCopy());}if(request.has("storyTime"))result.set("storyTime",request.path("storyTime").deepCopy());return result;}
    private ObjectNode visualCanon(JsonNode asset){ObjectNode result=mapper.createObjectNode();for(String field:List.of("id","identityId","lookId","characterId","name","description","identityTraits","locationBible","propBible","state"))if(asset.has(field))result.set(field,asset.path(field));return result;}

    private List<JsonNode> references(Shot shot,JsonNode request,ContinuityPlan plan){
        List<JsonNode> characters=new ArrayList<>(),locations=new ArrayList<>(),props=new ArrayList<>(),refs=new ArrayList<>();JsonNode assets=request.path("assets"),selections=request.path("shot").path("referenceViews");
        JsonNode storyboard=request.path("approvedStoryboard");if(storyboard.isObject()&&storyboard.path("locked").asBoolean()&&storyboard.path("selected").asBoolean()&&"PASSED".equals(text(storyboard,"qcStatus"))){ObjectNode composition=referenceBinding("COMPOSITION_REFERENCE",required(storyboard,"id"),validUrl(required(storyboard,"providerUrl")),required(storyboard,"id"),storyboard.path("version").asInt(),"只控制机位、景别、构图、站位和动作空间，不覆盖人物、地点或道具身份",List.of("composition","blocking","cameraAngle"),List.of("characterIdentity","wardrobe","locationIdentity","propIdentity"),88);composition.put("name","已批准构图预演").put("view","SHOT_COMPOSITION").put("setVersion",storyboard.path("version").asInt());refs.add(composition);}
        String locationView=selections.path(shot.locationId()).asText();if(!LocationViewProjection.isPerspective(locationView))throw new IllegalArgumentException("LAYOUT 俯视图只能作为空间辅助，不能作为透视镜头的主场景参考；请选择 "+String.join("、",LocationViewProjection.perspectiveViews()));
        for(String id:shot.characterIds()){JsonNode character=findAsset(assets,"characters",id);String lookId=required(plan.startState().path("characters").path(id),"lookId");JsonNode look=findAsset(assets,"looks",lookId);ObjectNode ref=selectedReference("CHARACTER_LOOK",look,selections.path(lookId).asText(),text(character,"name")+" / "+text(look,"name"));ref.put("characterId",id).put("subjectId",id);characters.add(ref);}
        JsonNode location=findAsset(assets,"locations",shot.locationId());locations.add(selectedReference("LOCATION",location,locationView,text(location,"name")));locations.add(selectedReference("LOCATION_LAYOUT",location,"LAYOUT",text(location,"name")+" / 固定空间布局（不直接入画）"));
        for(String id:shot.propIds()){JsonNode prop=findAsset(assets,"props",id);ObjectNode ref=selectedReference("PROP",prop,selections.path(id).asText(),text(prop,"name"));ref.put("subjectId",id);props.add(ref);}
        String requiredDetail=text(request.path("shot").path("visibilityPlan"),"requiredDetail");if("PROP_DETAIL".equals(requiredDetail)){refs.addAll(props);refs.addAll(locations);refs.addAll(characters);}else if("ESTABLISH_SPACE".equals(text(request.path("shot"),"directorIntent"))){refs.addAll(locations);refs.addAll(characters);refs.addAll(props);}else{refs.addAll(characters);refs.addAll(locations);refs.addAll(props);}return List.copyOf(refs);
    }
    private ObjectNode selectedReference(String role,JsonNode asset,String view,String name){if(view.isBlank())throw new IllegalArgumentException("分镜没有指定素材视角："+name);for(JsonNode item:asset.path("approvedViews"))if(view.equals(text(item,"view"))&&item.path("approved").asBoolean()&&!item.path("stale").asBoolean()){List<String> controls=switch(role){case "CHARACTER_LOOK"->List.of("characterIdentity","face","apparentAge","wardrobe");case "LOCATION"->List.of("locationIdentity","materials","perspective");case "LOCATION_LAYOUT"->List.of("worldTopology","fixedFacilityPosition");default->List.of("propIdentity","propGeometry","scale");};List<String> forbidden=switch(role){case "CHARACTER_LOOK"->List.of("pose","camera","background");case "LOCATION","LOCATION_LAYOUT"->List.of("characterIdentity","characterPose");default->List.of("holderPose","background");};int priority=switch(role){case "CHARACTER_LOOK"->100;case "LOCATION"->96;case "LOCATION_LAYOUT"->94;default->98;};ObjectNode ref=referenceBinding(role,required(item,"id"),required(item,"providerUrl"),required(asset,"id"),item.path("setVersion").asInt(),name,controls,forbidden,priority);ref.put("viewId",required(item,"id")).put("view",view).put("setVersion",item.path("setVersion").asInt()).put("name",name);return ref;}throw new IllegalArgumentException("缺少已批准的素材视角："+name+" / "+view);}
    private ObjectNode referenceBinding(String role,String referenceId,String url,String resourceId,int sourceVersion,String instructions,List<String> controls,List<String> forbidden,int priority){ObjectNode ref=mapper.createObjectNode().put("referenceId",referenceId).put("mediaType",role.contains("TAKE")?"VIDEO":"IMAGE").put("sourceResourceId",resourceId).put("sourceVersion",sourceVersion).put("role",role).put("assetId",resourceId).put("url",url).put("priority",priority).put("instructions",instructions).put("authorityPriority",priority);ArrayNode c=ref.putArray("controls");controls.forEach(c::add);ArrayNode m=ref.putArray("mustNotTransfer");forbidden.forEach(m::add);return ref;}
    private String referenceLegend(List<JsonNode> refs){List<String> entries=new ArrayList<>();for(int i=0;i<refs.size();i++){JsonNode r=refs.get(i);String authority=switch(text(r,"role")){case "COMPOSITION_REFERENCE"->"只约束机位、景别、构图、人物站位和动作空间关系；不得覆盖人物、场景或道具身份";case "LOCATION"->"当前机位透视主参考";case "LOCATION_LAYOUT"->"俯视空间辅助，仅用于世界坐标定位，不可照抄为当前画面";case "CHARACTER_LOOK"->"人物身份与定妆参考";case "PROP"->"道具形制参考";default->"制作参考";};entries.add("图"+(i+1)+"="+text(r,"name")+"，"+text(r,"view")+"，"+authority+"，版本"+r.path("sourceVersion").asInt());}return String.join("；",entries);}
    private String validUrl(String value){URI uri=URI.create(value);String scheme=uri.getScheme();if(!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("媒体引用必须是有效 HTTP(S) URL");return value;}
    private int takes(Difficulty difficulty){return switch(difficulty){case A->1;case B,C->2;case D->3;};}
    private String number(double value){return String.format(Locale.ROOT,"%.2f",value);}
}
