package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.yourapp.drama.production.ProductionJson.*;
import static com.yourapp.drama.production.ProductionModels.*;

/** Single provider-neutral production prompt pipeline: domain request -> PromptIR -> provider text. */
@Service
public class PromptCompiler {
    public static final String IMAGE_COMPILER_VERSION="4.0.0";
    public static final String VIDEO_COMPILER_VERSION="4.0.0-sequence";
    public static final String LIPSYNC_COMPILER_VERSION="4.0.0-lipsync";
    public static final String VOICE_COMPILER_VERSION="4.0.0-voice";
    private final ObjectMapper mapper;
    private final ContinuityEngine continuity;
    private final ProviderCapabilityRegistry capabilities;
    private final ProviderCompiler seedream;
    private final ProviderCompiler seedance;
    private final ProviderCompiler seedAudio;
    private final ProviderCompiler structuredText;
    private final SequenceBoundaryGate boundary=new SequenceBoundaryGate();

    @Autowired
    public PromptCompiler(ObjectMapper mapper,ContinuityEngine continuity,ProviderCapabilityRegistry capabilities,SeedreamCompiler seedream,SeedanceCompiler seedance,SeedAudioCompiler seedAudio,ArkStructuredTextCompiler structuredText){this.mapper=mapper;this.continuity=continuity;this.capabilities=capabilities;this.seedream=seedream;this.seedance=seedance;this.seedAudio=seedAudio;this.structuredText=structuredText;}
    public PromptCompiler(ObjectMapper mapper,ContinuityEngine continuity,ProviderCapabilityRegistry capabilities){this(mapper,continuity,capabilities,new SeedreamCompiler(),new SeedanceCompiler(),new SeedAudioCompiler(),new ArkStructuredTextCompiler());}
    public PromptCompiler(ObjectMapper mapper,ContinuityEngine continuity){this(mapper,continuity,new ProviderCapabilityRegistry());}

    public PromptResult compileImage(JsonNode request){
        Shot shot=shot(request,mapper);ContinuityPlan plan=checkedPlan(request);String task=text(request,"imageTaskType");if(task.isBlank())task="KEYFRAME";
        if(!Set.of("ASSET_REFERENCE","PREVIS","KEYFRAME","REPAIR_EDIT").contains(task))throw new IllegalArgumentException("IMAGE_TASK_TYPE_INVALID："+task);
        List<JsonNode> refs=references(shot,request,plan);JsonNode provider=request.path("providerCapabilities").isObject()?request.path("providerCapabilities"):capabilities.image();
        if(refs.size()>provider.path("maxImageRefs").asInt(10))throw new IllegalArgumentException("PROVIDER_CAPABILITY_EXCEEDED：图片参考数量超过当前适配器能力");
        ObjectNode projection=ShotProjectionContext.build(request.path("shot"));ObjectNode topology=SpatialTopologyContext.build(request.path("shot"),request.path("assets"));ObjectNode visible=visibleState(shot,plan);
        PromptIR ir=promptIR(task,shot,request,plan,refs,true);
        String taskPurpose=switch(task){case "PREVIS"->"视觉构图预演，只决定机位、站位、姿态和空间关系，不重设计人物与场景";case "REPAIR_EDIT"->"局部修复已存在关键帧，只改变指定错误，其余已正确维度全部保持";case "ASSET_REFERENCE"->"标准资产参考，不承载剧情表演";default->"正式关键帧，只绘制镜头第0秒的可见状态，后续动作留给视频阶段";};
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
        sections.add(section("DYNAMIC AVOID",String.join("；",ir.negativeConstraints()),82,true));
        sections.add(section("OUTPUT",outputProfile(request,"静态单帧，不含字幕、水印或额外人物"),80,true));
        if(!text(request,"revisionFeedback").isBlank())sections.add(section("REPAIR TARGET","上一版偏差："+text(request,"revisionFeedback")+"。只修正该偏差，不改变未被点名的身份、场景、构图和道具。",96,true));
        String prompt=seedream.compile(ir,sections);
        return new PromptResult(IMAGE_COMPILER_VERSION,seedream.provider(),prompt,refs,plan,null,takes(shot.difficulty()),ir);
    }

    public PromptResult compileVideo(JsonNode request){
        Shot shot=shot(request,mapper);ContinuityPlan plan=checkedPlan(request);JsonNode prepared=request.path("preparedVideo").isObject()?request.path("preparedVideo"):prepareVideo(request);Strategy strategy=Strategy.valueOf(prepared.path("strategy").asText());
        List<JsonNode> refs=new ArrayList<>();prepared.path("references").forEach(refs::add);
        List<String> carriers=promptCarriers(request.path("shot"));boundary.validate(request,refs,carriers);
        PromptIR ir=promptIR("VIDEO",shot,request,plan,refs,false);
        List<PromptBudgeter.Section> sections=new ArrayList<>();
        sections.add(section("SHOT INTENT / CARRIERS","shotPurpose="+shot.purpose()+"\nfeltIntent="+text(request.path("shot"),"feltIntent")+"\nvisible carriers="+carriers,100,true));
        if(!text(request,"runtimeProviderRules").isBlank())sections.add(section("PROVIDER TASK RULES",text(request,"runtimeProviderRules"),78,false));
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
        sections.add(section("DYNAMIC AVOID",String.join("；",ir.negativeConstraints()),89,true));
        sections.add(section("AUDIO POLICY","generate_audio=false。视频只负责可见表演、呼吸与开口时机；对白、环境声、拟音和音乐由 TTS/LipSync/PostProduction 统一完成。",88,true));
        sections.add(section("OUTPUT CONSTRAINTS","一个 Shot 对应一个连续 Video Take；不得在片内剪成多镜头；结尾稳定可衔接",87,true));
        if(request.path("retake").isObject())sections.add(section("ONE-VARIABLE RETAKE","issueCodes="+request.path("retake").path("issueCodes")+"；repairStrategy="+text(request.path("retake"),"repairStrategy")+"；只修改 dimensions="+request.path("retake").path("repairPlan").path("repairDimensions")+" / sections="+request.path("retake").path("changedPromptSections")+"；必须保持 dimensions="+request.path("retake").path("repairPlan").path("preserveDimensions")+"。",97,true));
        String prompt=seedance.compile(ir,sections);
        return new PromptResult(VIDEO_COMPILER_VERSION,seedance.provider(),prompt,List.copyOf(refs),plan,strategy,takes(shot.difficulty()),ir);
    }

    /** Formal compilation entry for approved character, location and prop reference views. */
    public AuxiliaryPrompt compileAssetReference(JsonNode request){
        String kind=requiredText(request,"assetKind"),assetId=requiredText(request,"assetId"),view=requiredText(request,"view");
        if(!Set.of("CHARACTER_LOOK","LOCATION","PROP").contains(kind))throw new IllegalArgumentException("未知素材类型："+kind);
        JsonNode source=request.path("sourceSnapshot");if(!source.isObject()||source.isEmpty())throw new IllegalArgumentException("ASSET_SOURCE_SNAPSHOT_REQUIRED");
        JsonNode camera=request.path("viewCamera");if(!camera.isObject()||camera.isEmpty())throw new IllegalArgumentException("ASSET_VIEW_CAMERA_REQUIRED");
        List<JsonNode> refs=new ArrayList<>();request.path("referenceBindings").forEach(value->refs.add(value.deepCopy()));
        PromptIR ir=assetPromptIR(kind,assetId,view,source,camera,request.path("referenceBindings"),refs);
        List<PromptBudgeter.Section> sections=new ArrayList<>();
        if("LOCATION".equals(kind)&&!refs.isEmpty())sections.add(section("TARGET VIEW — HIGHEST PRIORITY",locationCameraInstruction(view)+"目标 viewCamera 是构图与投影的最高约束；参考图只提供空间身份，不得继承参考图的观察方向、取景位置、画面布局或可见面。",100,true));
        String feedback=text(request,"revisionFeedback");if(!feedback.isBlank())sections.add(section("REVISION TARGET","上一版可见偏差："+feedback+"。本次必须纠正此偏差，未点名的身份与几何保持不变。",99,true));
        sections.add(section("TARGET VIEW","本视角："+camera+"。人物或物件保持同一世界朝向，只移动相机。",99,true));
        sections.add(section("ASSET IDENTITY",assetAnchor(kind)+"\nsourceSnapshot="+source,100,true));
        sections.add(section("REFERENCE AUTHORITY",refs.isEmpty()?"这是本套多视图的主参考，生成后需人工确认后才能成为其它视角锚点。":"输入参考图是已经确认的同一素材锚点；严格保持各 referenceBindings 声明的身份、几何、材质和纹理权限，只改变观察视角。referenceBindings="+request.path("referenceBindings"),100,true));
        sections.add(section("SKILL CONTRACT",loadAssetPrompt(kind),92,true));
        sections.add(section("EXECUTION METADATA","assetKind="+kind+"；assetId="+assetId+"；view="+view+"；referenceViewIds="+request.path("referenceViewIds")+"。这些参数不得显示在图片中，也不得输出 JSON。",85,true));
        sections.add(section("OUTPUT","只生成一张单视角制作参考图，不要拼图、四宫格、重复主体、文字水印或 JSON。",98,true));
        String prompt=seedream.compile(ir,sections);
        String version=text(request,"compilerVersion");if(version.isBlank())version=IMAGE_COMPILER_VERSION+"-asset-reference";
        return auxiliary(version,seedream.provider(),prompt,ir);
    }

    /** Formal compilation entry for video-preserving dialogue lip synchronization. */
    public AuxiliaryPrompt compileLipSync(JsonNode request){
        String shotId=requiredText(request,"shotId"),takeId=requiredText(request,"sourceTakeId");
        List<JsonNode> refs=new ArrayList<>();request.path("references").forEach(value->refs.add(value.deepCopy()));
        if(refs.stream().noneMatch(ref->"reference_video".equals(text(ref,"role"))))throw new IllegalArgumentException("LIPSYNC_VIDEO_REFERENCE_REQUIRED");
        if(refs.stream().noneMatch(ref->"reference_audio".equals(text(ref,"role"))))throw new IllegalArgumentException("LIPSYNC_AUDIO_REFERENCE_REQUIRED");
        ObjectNode intent=mapper.createObjectNode().put("shotId",shotId).put("sourceTakeId",takeId).put("purpose","LIPSYNC");
        ObjectNode preserve=mapper.createObjectNode().put("preserve",true).put("sourceTakeId",takeId);
        ObjectNode audio=mapper.createObjectNode().put("generateAudio",false).put("policy","REFERENCE_AUDIO_DRIVES_LIP_SYNC");audio.set("audioClipIds",request.path("audioClipIds").deepCopy());
        ObjectNode action=mapper.createObjectNode().put("currentAction","仅校正说话口型与面部微表情").put("endpoint","镜头时长、表演动作和画面结尾保持源视频不变");
        PromptIR ir=new PromptIR("LIPSYNC",intent,empty(),empty(),empty(),preserve.deepCopy(),preserve.deepCopy(),preserve.deepCopy(),preserve.deepCopy(),action,empty(),preserve.deepCopy(),preserve.deepCopy(),preserve.deepCopy(),preserve.deepCopy(),audio,empty(),List.of("新增台词","新增人物","改变身份或服装","改变场景或构图","改变动作或镜头长度","新增音乐"),mapper.createObjectNode().put("preserveDuration",true).put("generateAudio",false),List.copyOf(refs));
        List<PromptBudgeter.Section> sections=List.of(
                section("REFERENCE AUTHORITY","reference_video 是唯一画面与时长来源；reference_audio 只控制对应对白的口型时序。references="+request.path("references"),100,true),
                section("CURRENT ACTION / ENDPOINT","仅根据参考对白音频校正说话口型与面部微表情；镜头结尾必须与源视频一致。",100,true),
                section("HIGH-RISK CONTINUITY LOCKS","保持参考视频的人物身份、脸、服装、场景、构图、动作、机位、光线和镜头长度；不得新增或删除可见物。",100,true),
                section("AUDIO ALIGNMENT","audioClipIds="+request.path("audioClipIds")+"；不得添加、改写或重复台词，不生成音乐。",95,true),
                section("OUTPUT CONSTRAINTS","只输出一个与源视频等长的口型同步视频。",95,true));
        return auxiliary(LIPSYNC_COMPILER_VERSION,seedance.provider(),seedance.compile(ir,sections),ir);
    }

    /** Formal compilation entry for one approved dialogue line. Semantic/subtitle text remain audit data only. */
    public AuxiliaryPrompt compileVoice(JsonNode request){
        String dialogueId=requiredText(request,"dialogueId"),spoken=requiredText(request,"spokenText").replaceAll("\\s+"," ").trim();
        if(spoken.length()>1200)throw new IllegalArgumentException("SPOKEN_TEXT_TOO_LONG");
        double speed=request.path("speed").asDouble(.95);if(!Double.isFinite(speed)||speed<.5||speed>2)throw new IllegalArgumentException("INVALID_SPEED");
        String dialect=text(request,"dialect"),referenceMode=text(request,"voiceReferenceMode");
        if(!Set.of("PROVIDER_VOICE_ID","REFERENCE_AUDIO").contains(referenceMode))throw new IllegalArgumentException("VOICE_REFERENCE_MODE_REQUIRED");
        ObjectNode dialogue=mapper.createObjectNode().put("dialogueId",dialogueId).put("semanticText",text(request,"semanticText")).put("spokenText",spoken).put("subtitleText",text(request,"subtitleText"));
        ObjectNode audio=mapper.createObjectNode().put("voiceProfileId",text(request,"voiceProfileId")).put("voiceReferenceMode",referenceMode).put("dialect",dialect).put("speed",speed).put("singleSpeaker",true).put("dryVoiceOnly",true);
        ObjectNode intent=mapper.createObjectNode().put("dialogueId",dialogueId).put("purpose","VOICE");
        PromptIR ir=new PromptIR("VOICE",intent,empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),audio,dialogue,List.of("额外台词","角色名","制作说明","旁白","背景音乐","环境音效","第二说话者"),mapper.createObjectNode().put("format","mp3").put("sampleRate",48000),List.of());
        String identity="REFERENCE_AUDIO".equals(referenceMode)?"说话者音色严格采用 @音频1，只继承声纹身份，不朗读参考音频内容。":"说话者音色严格采用 @音频1（已批准的 provider voice profile），不改变说话者身份。";
        List<PromptBudgeter.Section> sections=List.of(
                section("VOICE IDENTITY",identity,100,true),
                section("SPEECH CONTENT","唯一允许说出的对白："+spoken,100,true),
                section("DELIVERY","发音要求："+(dialect.isBlank()?"自然普通话":dialect)+"；速度倍率="+speed+"。保持自然表演，不增加、改写或重复对白。",95,true),
                section("OUTPUT CONSTRAINTS","真人短剧单人干声；禁止朗读制作说明、角色名、旁白或额外台词；不生成背景音乐、环境声或音效。",100,true));
        return auxiliary(VOICE_COMPILER_VERSION,seedAudio.provider(),seedAudio.compile(ir,sections),ir);
    }

    public StructuredPrompt compileDirector(String stage,JsonNode input,JsonNode schema,String runtimeRules){
        if(!Set.of("DIRECTOR_PLAN","SHOT_DETAIL").contains(stage))throw new IllegalArgumentException("DIRECTOR_STAGE_INVALID："+stage);
        String context="以下为本场按空间、素材依赖和表演复杂度选择的导演规则正文：\n"+Objects.toString(runtimeRules,"")+"\n当前阶段："+stage+"。只完成该阶段 Schema 要求的内容。";
        return compileStructured(stage,"development-skills/06-shot-planning/prompt.md",input,schema,context,"4.0.0-director");
    }

    public StructuredPrompt compileStory(String type,JsonNode input,JsonNode schema){
        return compileStructured(type,storySkillResource(type),input,schema,"","4.0.0-story");
    }

    /** Used by RulePack resolution so its fingerprint covers the exact canonical skill text. */
    public String storySkillText(String type){return loadPromptResource(storySkillResource(type));}

    private StructuredPrompt compileStructured(String taskType,String skillResource,JsonNode input,JsonNode schema,String runtimeContext,String version){
        if(input==null||!input.isContainerNode())throw new IllegalArgumentException("STRUCTURED_PROMPT_INPUT_REQUIRED");
        if(schema==null||!schema.isObject())throw new IllegalArgumentException("STRUCTURED_PROMPT_SCHEMA_REQUIRED");
        ObjectNode intent=mapper.createObjectNode().put("purpose",taskType).put("phase",text(input,"phase"));
        ObjectNode subject=mapper.createObjectNode().put("skillResource",skillResource).put("inputChars",input.toString().length()).put("inputHash",sha256(input.toString()));
        for(String field:List.of("phase","documentId","rootPlanJobId","sceneTargetDurationSeconds"))if(input.has(field))subject.set(field,input.path(field).deepCopy());
        if(input.path("scene").hasNonNull("id"))subject.put("sceneId",text(input.path("scene"),"id"));
        ObjectNode output=mapper.createObjectNode().put("structuredJson",true);output.set("schema",schema.deepCopy());
        PromptIR ir=new PromptIR(taskType,intent,empty(),empty(),empty(),subject,empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),empty(),List.of("输出 Schema 外字段","省略必填字段","改写已锁定事实"),output,List.of());
        List<PromptBudgeter.Section> sections=new ArrayList<>();sections.add(section("SKILL CONTRACT",loadPromptResource(skillResource),100,true));if(runtimeContext!=null&&!runtimeContext.isBlank())sections.add(section("RUNTIME RULES AND STAGE",runtimeContext,100,true));
        String system=structuredText.compile(ir,sections);return new StructuredPrompt(version,structuredText.provider(),system,input.toString(),schema.deepCopy(),ir,mapper.valueToTree(ir));
    }

    private String storySkillResource(String type){return switch(type){
        case "STORY_BRIEF"->"development-skills/vendor/oiuv-ai-short-drama/script-brief/SKILL.md";
        case "PREMISE"->"development-skills/00-premise-analysis/prompt.md";
        case "CORE"->"development-skills/01-story-planning/prompt.md";
        case "OUTLINE_BATCH"->"development-skills/03-episode-planning/prompt.md";
        case "STORY_QA"->"development-skills/05-story-quality/prompt.md";
        default->"development-skills/04-script-writing/prompt.md";};}

    private String loadPromptResource(String resource){
        if(resource==null||!resource.startsWith("development-skills/")||resource.contains(".."))throw new IllegalArgumentException("PROMPT_RESOURCE_INVALID");
        try(var stream=new ClassPathResource(resource).getInputStream()){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        catch(Exception error){throw new IllegalStateException("Prompt 技能加载失败："+resource,error);}
    }
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}

    public ObjectNode prepareVideo(JsonNode request){
        Shot shot=shot(request,mapper);ContinuityPlan plan=checkedPlan(request);JsonNode route=routeVideo(request);ObjectNode prepared=mapper.createObjectNode().put("strategy",route.path("strategy").asText());ArrayNode refs=prepared.putArray("references");references(shot,request,plan).forEach(refs::add);route.path("references").forEach(refs::add);return prepared;
    }

    private AuxiliaryPrompt auxiliary(String version,String modelFamily,String prompt,PromptIR ir){return new AuxiliaryPrompt(version,modelFamily,prompt,ir,mapper.valueToTree(ir));}
    private ObjectNode empty(){return mapper.createObjectNode();}
    private PromptIR assetPromptIR(String kind,String assetId,String view,JsonNode source,JsonNode camera,JsonNode bindings,List<JsonNode> refs){
        ObjectNode intent=mapper.createObjectNode().put("assetKind",kind).put("assetId",assetId).put("view",view).put("purpose","CANONICAL_ASSET_REFERENCE");
        ObjectNode subject=mapper.createObjectNode().put("assetKind",kind).put("assetId",assetId);
        ObjectNode identity=mapper.createObjectNode();identity.set("sourceSnapshot",source.deepCopy());
        ObjectNode continuityIr=mapper.createObjectNode();continuityIr.set("referenceBindings",bindings.deepCopy());continuityIr.put("worldOrientationFixed",true);
        ObjectNode output=mapper.createObjectNode().put("imageCount",1).put("singleView",true).put("allowText",false).put("allowGrid",false);
        JsonNode characters="CHARACTER_LOOK".equals(kind)?identity.deepCopy():empty();JsonNode location="LOCATION".equals(kind)?identity.deepCopy():empty();JsonNode props="PROP".equals(kind)?identity.deepCopy():empty();
        return new PromptIR("ASSET_REFERENCE",intent,characters,location,props,subject,identity,"CHARACTER_LOOK".equals(kind)?identity.deepCopy():empty(),"LOCATION".equals(kind)?identity.deepCopy():empty(),empty(),empty(),empty(),camera.deepCopy(),empty(),continuityIr,empty(),empty(),List.of("镜像身份或不对称特征","改变世界朝向","新增、遗漏、复制或移动固定结构","拼图或重复主体","文字水印"),output,refs);
    }
    private String assetAnchor(String kind){return switch(kind){
        case "CHARACTER_LOOK"->"这是角色身份与定妆参考图。角色身份锚点与服装定妆分开：不得把手持道具、场景、剧情动作或临时姿势固化到人物主图；基础身份主图只确认脸、发型、体态和比例，服装主图只在此身份上确认本套衣服。人物保持同一世界朝向，只移动相机；脸侧、耳朵、肩膀、手和不对称特征必须服从 viewCamera。固定配饰不得换边，例如左肩到右胯的背带在四视图中始终连接同一身体锚点，不能按画面斜线照抄。";
        case "LOCATION"->"这是空场景参考图。locationBible 是唯一空间真相：逐个核对 surfaces 的承载面，以及每个 fixedFeatures.featureId 的 supportSurfaceId、worldPosition、size、state、appearance；不得遗漏、增添、复制或换面。prohibitedElements 中的内容不得出现。所有入口、出口、门窗、道路、井、树和固定地标按同一世界坐标保留；门窗必须保持同一开关状态；不同视角只能改变相机位置，不能旋转、镜像或移动建筑。目标 viewCamera 是构图与投影的最高约束；参考图只提供空间身份，不得继承参考图的观察方向、取景位置、画面布局或可见面。";
        default->"这是单件道具参考图。保持轮廓、尺寸比例、材质、重量感、颜色、纹理、刻痕、磨损和当前状态；身份文字、标记和图案必须保持，只有当目标侧面因几何遮挡确实不可见时才可不显示；不要出现手、人或第二件同类道具。";};}
    private String locationCameraInstruction(String view){return switch(view){
        case "FRONT"->"相机位于主活动区北侧并朝南；北侧边界位于相机身后，不能成为画面正前方主体；画面深度必须从北侧近景延伸到南侧远景。必须从该机位重新投影世界坐标，不能复刻布局主图或其它视图的构图。";
        case "REVERSE"->"相机位于主活动区南侧并朝北；南侧边界位于相机身后，画面深度必须从南侧近景延伸到北侧远景；必须与 FRONT 形成相反观察方向，不能生成同向近似画面。";
        case "SIDE"->"相机位于主活动区东侧并朝西；东侧边界位于相机身后，画面深度必须从东侧近景延伸到西侧远景；必须呈现真正的横向空间关系，不能生成朝北或朝南的近似画面。";
        default->"";};}
    private String loadAssetPrompt(String kind){
        String folder=switch(kind){case "CHARACTER_LOOK"->"09-character-design";case "LOCATION"->"10-location-design";case "PROP"->"11-prop-design";default->throw new IllegalArgumentException("未知素材类型："+kind);};
        try(var stream=new ClassPathResource("development-skills/"+folder+"/prompt.md").getInputStream()){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        catch(Exception error){throw new IllegalStateException("素材多视图技能加载失败："+folder,error);}
    }
    private String requiredText(JsonNode value,String field){String result=text(value,field);if(result.isBlank())throw new IllegalArgumentException(field+" REQUIRED");return result;}

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
    private PromptIR promptIR(String taskType,Shot shot,JsonNode request,ContinuityPlan plan,List<JsonNode> refs,boolean image){
        ObjectNode intent=mapper.createObjectNode().put("shotId",shot.shotId()).put("purpose",shot.purpose()).put("visualFocus",shot.visualFocus()).put("emotion",shot.emotion()).put("relationToPrevious",shot.relationToPrevious().name());
        String feltIntent=text(request.path("shot"),"feltIntent");if(!feltIntent.isBlank())intent.put("feltIntent",feltIntent);
        ObjectNode characters=mapper.createObjectNode(),identities=mapper.createObjectNode(),costumes=mapper.createObjectNode();
        for(String id:shot.characterIds()){
            ObjectNode constraint=plan.startState().path("characters").path(id).isObject()?(ObjectNode)plan.startState().path("characters").path(id).deepCopy():mapper.createObjectNode();
            JsonNode identity=findAsset(request.path("assets"),"characters",id);JsonNode identityCanon=visualCanon(identity);constraint.set("identity",identityCanon);identities.set(id,identityCanon.deepCopy());
            String lookId=text(constraint,"lookId");if(!lookId.isBlank()){JsonNode lookCanon=visualCanon(findAsset(request.path("assets"),"looks",lookId));constraint.set("look",lookCanon);costumes.set(id,lookCanon.deepCopy());}
            characters.set(id,constraint);
        }
        ObjectNode location=mapper.createObjectNode().put("locationId",shot.locationId());
        location.set("identity",visualCanon(findAsset(request.path("assets"),"locations",shot.locationId())));
        if(plan.startState().has("locationState"))location.set("state",plan.startState().path("locationState").deepCopy());
        location.set("topology",SpatialTopologyContext.build(request.path("shot"),request.path("assets")));
        ObjectNode props=mapper.createObjectNode();
        for(String id:shot.propIds()){
            ObjectNode constraint=plan.startState().path("props").path(id).isObject()?(ObjectNode)plan.startState().path("props").path(id).deepCopy():mapper.createObjectNode();
            constraint.set("identity",visualCanon(findAsset(request.path("assets"),"props",id)));props.set(id,constraint);
        }
        ObjectNode action=mapper.createObjectNode().put("currentAction",shot.action()).put("endpoint",endpoint(request.path("shot")));
        for(String field:List.of("performancePlan","timedBeats","stateChanges","completedBeats","reservedFutureBeats"))if(request.path("shot").has(field))action.set(field,request.path("shot").path(field).deepCopy());
        ObjectNode camera=request.path("shot").path("cameraPlan").isObject()?(ObjectNode)request.path("shot").path("cameraPlan").deepCopy():mapper.createObjectNode();
        camera.put("shotSize",shot.shotSize()).put("cameraAngle",shot.cameraAngle()).put("cameraMovement",shot.cameraMovement());
        ObjectNode continuityIr=mapper.createObjectNode();continuityIr.set("startState",plan.startState().deepCopy());continuityIr.set("inheritedConstraints",plan.inheritedConstraints().deepCopy());continuityIr.set("allowedChanges",mapper.valueToTree(plan.allowedChanges()));continuityIr.set("risks",mapper.valueToTree(plan.risks()));continuityIr.put("riskScore",plan.riskScore());
        ObjectNode subject=mapper.createObjectNode().put("visualFocus",shot.visualFocus());ArrayNode subjectCharacters=subject.putArray("characterIds");shot.characterIds().forEach(subjectCharacters::add);ArrayNode subjectProps=subject.putArray("propIds");shot.propIds().forEach(subjectProps::add);
        ObjectNode emotion=mapper.createObjectNode().put("intended",shot.emotion());
        JsonNode blocking=request.path("shot").path("blocking").deepCopy();
        ObjectNode lighting=mapper.createObjectNode().put("direction",text(request.path("shot").path("cameraPlan"),"lightingDirection")).put("state",text(plan.startState(),"lighting"));
        ObjectNode audio=mapper.createObjectNode().put("generateAudio",false).put("policy","TTS_LIPSYNC_POST_PRODUCTION");
        ObjectNode dialogue=mapper.createObjectNode();dialogue.set("dialogueIds",mapper.valueToTree(shot.dialogueIds()));if(request.has("dialogues"))dialogue.set("entries",request.path("dialogues").deepCopy());
        JsonNode profile=request.path("outputProfile").isObject()?request.path("outputProfile"):request.path("videoOutputProfile");
        return new PromptIR(taskType,intent,characters,location,props,subject,identities,costumes,location.deepCopy(),action,emotion,blocking,camera,lighting,continuityIr,audio,dialogue,List.copyOf(dynamicAvoid(shot,request,image)),profile.deepCopy(),refs.stream().map(node->(JsonNode)node.deepCopy()).toList());
    }
    private ContinuityPlan checkedPlan(JsonNode request){ContinuityPlan plan=continuity.plan(request);if(!plan.passed())throw new IllegalArgumentException("连续性检查未通过: "+plan.risks());return plan;}
    private String identityLocks(Shot shot,JsonNode request,ContinuityPlan plan){StringBuilder out=new StringBuilder();for(String id:shot.characterIds()){JsonNode actor=findAsset(request.path("assets"),"characters",id),state=plan.startState().path("characters").path(id);String lookId=text(state,"lookId");JsonNode look=findAsset(request.path("assets"),"looks",lookId);out.append(id).append(": identity source=approved CHARACTER_LOOK ").append(lookId).append("；preserve face/feature proportions/apparent age/body/distinctive features；current look=").append(visualCanon(look)).append("；identity=").append(visualCanon(actor)).append('\n');}return out.toString();}
    private String propLocks(Shot shot,JsonNode request,ContinuityPlan plan){StringBuilder out=new StringBuilder();for(String id:shot.propIds()){JsonNode prop=findAsset(request.path("assets"),"props",id),state=plan.startState().path("props").path(id);out.append(id).append(": canon=").append(visualCanon(prop)).append("；holder=").append(text(state,"holder")).append("；hand=").append(text(state,"heldByHand")).append("；position=").append(text(state,"position")).append("；state=").append(text(state,"state")).append('\n');}return out.toString();}
    private String imagePose(String task,Shot shot,JsonNode request){String value=directorInstructions(request)+"\n姿态、视线与接触点来自 blocking/performance/startState。";if("PREVIS".equals(task))value+=" 构图预演可表达当前动作意图："+shot.action();return value;}
    private String physicalLighting(JsonNode request){JsonNode camera=request.path("shot").path("cameraPlan"),state=request.path("shot").path("startState");return "physical source/direction="+text(camera,"lightingDirection")+"；scene lighting="+text(state,"lighting")+"；保持色温关系、阴影方向和实际光源位置，不用空泛氛围词替代。";}
    private String outputProfile(JsonNode request,String rule){JsonNode profile=request.path("outputProfile").isObject()?request.path("outputProfile"):request.path("videoOutputProfile");return rule+"；profile="+profile+"；providerCapabilitiesVersion="+request.path("providerCapabilities").path("version").asText(ProviderCapabilityRegistry.VERSION);}
    private String sourceLineage(JsonNode request,Strategy strategy){JsonNode previous=request.path("previousTake");return "sequenceRelation="+text(request.path("shot"),"sequenceRelation")+"；strategy="+strategy+"；canonical source=approved Character/Look/Location/Prop；continuity source="+(previous.isObject()?"accepted take "+text(previous,"id")+" depth="+previous.path("continuationDepth").asInt():"current locked keyframe")+"。上一视频的轻微漂移不得晋升为 canonical identity。";}
    private String openingState(JsonNode request,ContinuityPlan plan,Shot shot){JsonNode observed=request.path("previousTake").path("observedState"),source=observed.isObject()&&!observed.isEmpty()?observed:visibleState(shot,plan);return visibleSequenceState(source,shot)+"。Source carries state; text only carries the current delta. 不重新描述全部历史。";}
    private String endpoint(JsonNode shot){String explicit=text(shot,"endpoint");return explicit.isBlank()?shot.path("endState").toString():explicit;}
    private String timedBeats(Shot shot,JsonNode raw){if(raw.path("timedBeats").isArray()&&!raw.path("timedBeats").isEmpty())return raw.path("timedBeats").toString();double a=Math.max(.4,shot.duration()*.2),b=Math.max(a+.4,shot.duration()*.75);return "0.0–"+number(a)+"s 保持实际起始姿态；"+number(a)+"–"+number(b)+"s 完成一次主要动作「"+shot.action()+"」；"+number(b)+"–"+number(shot.duration())+"s 到达 endpoint 并稳定停留。";}
    private String cameraContract(Shot shot,JsonNode raw){JsonNode camera=raw.path("cameraPlan");return "shotScale="+shot.shotSize()+"；angle="+shot.cameraAngle()+"；唯一主运镜="+shot.cameraMovement()+"；speed="+text(camera,"movementSpeed")+"；path="+text(camera,"movementPath")+"；subjectRelationship="+text(camera,"subjectPlacement")+"；endpoint="+text(camera,"focusPoint")+"。不得叠加第二套主运镜；CONTINUOUS 不得无授权越轴或反转屏幕方向。";}
    private String physicsContract(Shot shot,JsonNode request,ContinuityPlan plan){JsonNode performance=request.path("shot").path("performancePlan"),state=visibleState(shot,plan);return "actor→action→contact→consequence→endpoint："+performance+"；start contact state="+state.path("props")+"。手、道具、门、水、布料和身体接触遵守连续物理；道具只能在显式接触与转移后改变持有人或手位。";}
    private String stateTransitions(Shot shot,JsonNode raw){JsonNode changes=raw.path("stateChanges").isArray()?raw.path("stateChanges"):raw.path("authorizedChanges");if(!changes.isArray()||changes.isEmpty())return "本镜无授权状态变化；所有服装、伤痕、湿度、破损、门窗和持物保持到 endpoint。";StringBuilder out=new StringBuilder();for(JsonNode change:changes){double at=change.path("atSeconds").asDouble(shot.duration()*.6);String from=stateValue(change.path("from")),to=stateValue(change.path("to"));out.append("before ").append(number(at)).append("s keep ").append(text(change,"path")).append('=').append(from).append("；at ").append(number(at)).append("s because ").append(text(change,"reason")).append(" → ").append(to).append("；forbidden before trigger=").append(to).append('\n');}return out.toString();}
    private String stateValue(JsonNode value){return value.isContainerNode()?value.toString():value.asText("");}
    private String listOrNone(JsonNode values,String rule){return rule+"："+(values.isArray()&&!values.isEmpty()?values:"[]");}
    private List<String> promptCarriers(JsonNode shot){List<String> values=new ArrayList<>();collectStrings(shot.path("promptCarriers"),values);if(values.isEmpty()){collectStrings(shot.path("performancePlan"),values);collectStrings(shot.path("cameraPlan"),values);String lighting=text(shot.path("cameraPlan"),"lightingDirection");if(!lighting.isBlank())values.add("lighting="+lighting);}return values.stream().filter(v->!v.isBlank()).distinct().limit(12).toList();}
    private void collectStrings(JsonNode node,List<String> out){if(node.isTextual()){String value=node.asText().trim();if(!value.isBlank())out.add(value);}else if(node.isArray())node.forEach(v->collectStrings(v,out));else if(node.isObject())node.fields().forEachRemaining(e->collectStrings(e.getValue(),out));}
    private List<String> dynamicAvoid(Shot shot,JsonNode request,boolean image){LinkedHashSet<String> avoid=new LinkedHashSet<>(List.of("identity drift","wardrobe redesign","extra or missing characters","duplicate limbs","unplanned camera move"));JsonNode state=shot.startState();if(shot.characterIds().size()>1)avoid.add("identity swap between characters");for(String prop:shot.propIds()){JsonNode p=state.path("props").path(prop);String hand=text(p,"heldByHand");if(!hand.isBlank()){avoid.add("wrong hand for "+prop);avoid.add(prop+" detached from the specified grip");}avoid.add("missing or duplicated "+prop);}if(request.path("shot").path("blocking").isObject()){avoid.add("mirrored screen direction");avoid.add("body entering from the wrong side");}if(image){avoid.add("moving doors/windows/fixed facilities to another wall");avoid.add("background topology drift");}else{avoid.add("repeating completed action");avoid.add("performing reserved future beat");avoid.add("state change before its trigger");}return avoid.stream().limit(18).toList();}
    private String referenceAuthority(List<JsonNode> refs){ArrayNode compact=mapper.createArrayNode();for(JsonNode ref:refs){ObjectNode item=mapper.createObjectNode();for(String field:List.of("referenceId","role","subjectId","sourceResourceId","sourceVersion","controls","mustNotTransfer","authorityPriority","instructions"))if(ref.has(field))item.set(field,ref.path(field).deepCopy());compact.add(item);}return compact.toString();}
    private String directorInstructions(JsonNode request){JsonNode shot=request.path("shot");StringBuilder value=new StringBuilder("导演方案：意图 ").append(text(shot,"directorIntent")).append("；镜头存在理由 ").append(text(shot,"shotPurpose")).append("；主体 ").append(text(shot,"subject")).append("；次要主体 ").append(shot.path("secondarySubjects")).append("；对焦 ").append(text(shot,"focus")).append("；视线 ").append(text(shot,"eyeLine")).append("；对白说话者 ").append(text(shot,"dialogueOwner")).append("。\n空间调度：").append(shot.path("blocking")).append("。\n表演方案：").append(shot.path("performancePlan")).append("；可见性方案：").append(shot.path("visibilityPlan"));JsonNode beat=shot.path("dramaticBeatSnapshot");if(beat.isObject())value.append("。\n本节拍情绪从 ").append(text(beat,"emotionBefore")).append(" 推进到 ").append(text(beat,"emotionAfter")).append("；信息揭示：").append(text(beat,"informationReveal"));return value.toString();}
    private String constraints(Shot shot,ContinuityPlan plan,JsonNode assets){ObjectNode characters=mapper.createObjectNode(),props=mapper.createObjectNode();plan.inheritedConstraints().path("characters").fields().forEachRemaining(entry->{ObjectNode character=visualCanon(entry.getValue());character.set("wardrobe",visualCanon(entry.getValue().path("wardrobe")));characters.set(entry.getKey(),character);});plan.inheritedConstraints().path("props").fields().forEachRemaining(entry->props.set(entry.getKey(),visualCanon(entry.getValue())));return "不可丢失的固定视觉设定。Character/Look Bible="+characters+"；Location Bible="+visualCanon(plan.inheritedConstraints().path("location"))+"；Prop Bible="+props+"；Scene State="+visibleState(shot,plan)+"；Style Bible="+assets.path("style").asText("写实");}
    private ObjectNode visibleState(Shot shot,ContinuityPlan plan){ObjectNode state=plan.startState().deepCopy();if(state.path("characters").isObject())((ObjectNode)state.path("characters")).retain(shot.characterIds());if(state.path("props").isObject())((ObjectNode)state.path("props")).retain(shot.propIds());return state;}
    private ObjectNode visibleSequenceState(JsonNode source,Shot shot){ObjectNode state=mapper.createObjectNode();for(String field:List.of("locationId","time","lighting","spatialRelations","spatialAnchors"))if(source.has(field))state.set(field,source.path(field).deepCopy());ObjectNode characters=state.putObject("characters"),props=state.putObject("props");for(String id:shot.characterIds())if(source.path("characters").has(id))characters.set(id,source.path("characters").path(id).deepCopy());for(String id:shot.propIds())if(source.path("props").has(id))props.set(id,source.path("props").path(id).deepCopy());return state;}
    private ObjectNode narrativeContext(Shot shot,JsonNode request){ObjectNode result=mapper.createObjectNode(),characters=result.putObject("characterStates");for(String id:shot.characterIds())if(request.path("characterStates").has(id))characters.set(id,request.path("characterStates").path(id).deepCopy());result.set("storyFacts",relevantStoryFacts(shot,request));ArrayNode relationships=result.putArray("relationships");int count=0;for(JsonNode relationship:request.path("relationships")){String from=text(relationship,"fromCharacterId"),to=text(relationship,"toCharacterId");if((shot.characterIds().contains(from)||shot.characterIds().contains(to))&&count++<12)relationships.add(relationship.deepCopy());}if(request.has("storyTime"))result.set("storyTime",request.path("storyTime").deepCopy());return result;}
    private ArrayNode relevantStoryFacts(Shot shot,JsonNode request){
        Set<String> entities=new LinkedHashSet<>(shot.characterIds());entities.addAll(shot.propIds());if(!shot.locationId().isBlank())entities.add(shot.locationId());
        Set<String> explicit=new LinkedHashSet<>();collectFactSelectors(request.path("shot").path("storyFactIds"),explicit);collectFactSelectors(request.path("shot").path("dramaticBeatSnapshot").path("storyFactChanges"),explicit);
        record RankedFact(JsonNode value,int rank,double effectiveAt,int order){}
        List<RankedFact> ranked=new ArrayList<>();int order=0;
        for(JsonNode fact:request.path("storyFacts")){
            boolean selected=matchesFactSelector(fact,explicit),direct=entities.contains(text(fact,"subjectEntityId"))||entities.contains(text(fact,"objectEntityId")),known=knownByVisibleCharacter(fact.path("knownBy"),shot.characterIds());
            boolean legacy=!fact.has("subjectEntityId")&&!fact.has("objectEntityId")&&!fact.has("knownBy");int rank=selected?4:direct?3:known?2:legacy?1:0;
            if(rank>0)ranked.add(new RankedFact(fact,rank,fact.path("validFromStoryTime").asDouble(Double.NEGATIVE_INFINITY),order));order++;
        }
        ranked.sort(Comparator.comparingInt(RankedFact::rank).reversed().thenComparing(Comparator.comparingDouble(RankedFact::effectiveAt).reversed()).thenComparingInt(RankedFact::order));
        ArrayNode result=mapper.createArrayNode();for(RankedFact fact:ranked){if(result.size()==12)break;result.add(fact.value().deepCopy());}return result;
    }
    private void collectFactSelectors(JsonNode values,Set<String> target){if(values.isArray())for(JsonNode value:values)if(value.isValueNode()&&!value.asText().isBlank())target.add(value.asText());}
    private boolean matchesFactSelector(JsonNode fact,Set<String> selectors){if(selectors.isEmpty())return false;return selectors.contains(text(fact,"id"))||selectors.contains(text(fact,"factKey"))||selectors.contains(text(fact,"statement"));}
    private boolean knownByVisibleCharacter(JsonNode knownBy,List<String> characters){if(knownBy.isTextual())return characters.contains(knownBy.asText());if(knownBy.isArray())for(JsonNode actor:knownBy)if(characters.contains(actor.asText()))return true;return false;}
    private ObjectNode visualCanon(JsonNode asset){ObjectNode result=mapper.createObjectNode();for(String field:List.of("id","identityId","lookId","characterId","name","description","identityTraits","locationBible","propBible","state"))if(asset.has(field))result.set(field,asset.path(field));return result;}

    private List<JsonNode> references(Shot shot,JsonNode request,ContinuityPlan plan){
        List<JsonNode> characters=new ArrayList<>(),locations=new ArrayList<>(),props=new ArrayList<>(),refs=new ArrayList<>();JsonNode assets=request.path("assets"),selections=request.path("shot").path("referenceViews");
        JsonNode storyboard=request.path("approvedStoryboard");if(storyboard.isObject()&&storyboard.path("locked").asBoolean()&&storyboard.path("selected").asBoolean()&&"PASSED".equals(text(storyboard,"qcStatus"))){ObjectNode composition=referenceBinding("COMPOSITION_REFERENCE",required(storyboard,"id"),validUrl(required(storyboard,"providerUrl")),required(storyboard,"id"),storyboard.path("version").asInt(),"只控制机位、景别、构图、站位和动作空间，不覆盖人物、地点或道具身份",List.of("composition","blocking","cameraAngle"),List.of("characterIdentity","wardrobe","locationIdentity","propIdentity"),88);composition.put("name","已批准构图预演").put("view","SHOT_COMPOSITION").put("setVersion",storyboard.path("version").asInt());refs.add(composition);}
        String locationView=selections.path(shot.locationId()).asText();if(!LocationViewProjection.isPerspective(locationView))throw new IllegalArgumentException("LAYOUT 俯视图只能作为空间辅助，不能作为透视镜头的主场景参考；请选择 "+String.join("、",LocationViewProjection.perspectiveViews()));
        for(String id:shot.characterIds()){JsonNode character=findAsset(assets,"characters",id);String lookId=required(plan.startState().path("characters").path(id),"lookId");JsonNode look=findAsset(assets,"looks",lookId);ObjectNode ref=selectedReference("CHARACTER_LOOK",look,selections.path(lookId).asText(),text(character,"name")+" / "+text(look,"name"));ref.put("characterId",id).put("subjectId",id);characters.add(ref);}
        JsonNode location=findAsset(assets,"locations",shot.locationId());ObjectNode locationRef=selectedReference("LOCATION",location,locationView,text(location,"name"));locationRef.put("subjectId",shot.locationId());locations.add(locationRef);ObjectNode layoutRef=selectedReference("LOCATION_LAYOUT",location,"LAYOUT",text(location,"name")+" / 固定空间布局（不直接入画）");layoutRef.put("subjectId",shot.locationId());locations.add(layoutRef);
        for(String id:shot.propIds()){JsonNode prop=findAsset(assets,"props",id);ObjectNode ref=selectedReference("PROP",prop,selections.path(id).asText(),text(prop,"name"));ref.put("subjectId",id);props.add(ref);}
        String requiredDetail=text(request.path("shot").path("visibilityPlan"),"requiredDetail");if("PROP_DETAIL".equals(requiredDetail)){refs.addAll(props);refs.addAll(locations);refs.addAll(characters);}else if("ESTABLISH_SPACE".equals(text(request.path("shot"),"directorIntent"))){refs.addAll(locations);refs.addAll(characters);refs.addAll(props);}else{refs.addAll(characters);refs.addAll(locations);refs.addAll(props);}return List.copyOf(refs);
    }
    private ObjectNode selectedReference(String role,JsonNode asset,String view,String name){if(view.isBlank())throw new IllegalArgumentException("分镜没有指定素材视角："+name);for(JsonNode item:asset.path("approvedViews"))if(view.equals(text(item,"view"))&&item.path("approved").asBoolean()&&!item.path("stale").asBoolean()){List<String> controls=switch(role){case "CHARACTER_LOOK"->List.of("characterIdentity","face","apparentAge","wardrobe");case "LOCATION"->List.of("locationIdentity","materials","perspective");case "LOCATION_LAYOUT"->List.of("worldTopology","fixedFacilityPosition");default->List.of("propIdentity","propGeometry","scale");};List<String> forbidden=switch(role){case "CHARACTER_LOOK"->List.of("pose","camera","background");case "LOCATION","LOCATION_LAYOUT"->List.of("characterIdentity","characterPose");default->List.of("holderPose","background");};int priority=switch(role){case "CHARACTER_LOOK"->100;case "LOCATION"->96;case "LOCATION_LAYOUT"->94;default->98;};ObjectNode ref=referenceBinding(role,required(item,"id"),required(item,"providerUrl"),required(asset,"id"),item.path("setVersion").asInt(),name,controls,forbidden,priority);ref.put("viewId",required(item,"id")).put("view",view).put("setVersion",item.path("setVersion").asInt()).put("name",name);return ref;}throw new IllegalArgumentException("缺少已批准的素材视角："+name+" / "+view);}
    private ObjectNode referenceBinding(String role,String referenceId,String url,String resourceId,int sourceVersion,String instructions,List<String> controls,List<String> forbidden,int priority){ObjectNode ref=mapper.createObjectNode().put("referenceId",referenceId).put("mediaType",role.contains("TAKE")?"video_url":"image_url").put("sourceResourceId",resourceId).put("sourceVersion",sourceVersion).put("role",role).put("assetId",resourceId).put("url",url).put("priority",priority).put("instructions",instructions).put("authorityPriority",priority);ArrayNode c=ref.putArray("controls");controls.forEach(c::add);ArrayNode m=ref.putArray("mustNotTransfer");forbidden.forEach(m::add);return ref;}
    private String referenceLegend(List<JsonNode> refs){List<String> entries=new ArrayList<>();for(int i=0;i<refs.size();i++){JsonNode r=refs.get(i);String authority=switch(text(r,"role")){case "COMPOSITION_REFERENCE"->"只约束机位、景别、构图、人物站位和动作空间关系；不得覆盖人物、场景或道具身份";case "LOCATION"->"当前机位透视主参考";case "LOCATION_LAYOUT"->"俯视空间辅助，仅用于世界坐标定位，不可照抄为当前画面";case "CHARACTER_LOOK"->"人物身份与定妆参考";case "PROP"->"道具形制参考";default->"制作参考";};entries.add("图"+(i+1)+"="+text(r,"name")+"，"+text(r,"view")+"，"+authority+"，版本"+r.path("sourceVersion").asInt());}return String.join("；",entries);}
    private String validUrl(String value){URI uri=URI.create(value);String scheme=uri.getScheme();if(!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("媒体引用必须是有效 HTTP(S) URL");return value;}
    private int takes(Difficulty difficulty){return switch(difficulty){case A->1;case B,C->2;case D->3;};}
    private String number(double value){return String.format(Locale.ROOT,"%.2f",value);}
}
