package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.domain.ShotRelation;
import com.yourapp.drama.domain.Shot.Difficulty;
import com.yourapp.drama.provider.StructuredJson;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;

/** One director response contract with explicit scene state and persisted snapshot materialization. */
@Component
public final class DirectorContract {
    private static final List<String> SHOT_SIZES=List.of("EXTREME_WIDE","WIDE","FULL","MEDIUM_FULL","MEDIUM","MEDIUM_CLOSE","MEDIUM_CLOSE_UP","CLOSE_UP","EXTREME_CLOSE_UP","OVER_THE_SHOULDER","POV","INSERT");
    private static final List<String> CAMERA_ANGLES=List.of("EYE_LEVEL","HIGH","LOW","HIGH_ANGLE","LOW_ANGLE","TOP_DOWN","DUTCH","PROFILE","FRONTAL","BACK","OVER_SHOULDER","POV");
    private static final List<String> CAMERA_MOVEMENTS=List.of("STATIC","PAN","TILT","DOLLY_IN","DOLLY_OUT","TRACK","FOLLOW","HANDHELD","ORBIT");
    private static final List<String> DIRECTOR_INTENTS=List.of("ESTABLISH_SPACE","SHOW_ACTION","SHOW_REACTION","REVEAL_INFORMATION","BUILD_TENSION","EMPHASIZE_EMOTION","HIDE_INFORMATION","TRANSITION","PAYOFF");
    private static final List<String> TRANSITIONS=List.of("CUT","MATCH_CUT","DISSOLVE","FADE","WHIP_PAN","HOLD");
    private static final List<String> SCREEN_DIRECTIONS=List.of("FRAME_LEFT","FRAME_RIGHT","INTO_DEPTH","OUT_OF_DEPTH","STATIC");
    private static final List<String> VISIBLE_BODY_PARTS=List.of("WHOLE_BODY","FACE","TORSO","LEFT_HAND","RIGHT_HAND","BOTH_HANDS","LEFT_FOOT","RIGHT_FOOT","OTHER");
    private static final List<String> BODY_FRAME_SIDES=List.of("IN_FRAME_LEFT","IN_FRAME_CENTER","IN_FRAME_RIGHT","OFFSCREEN_LEFT","OFFSCREEN_RIGHT","OFFSCREEN_TOP","OFFSCREEN_BOTTOM","BEHIND_CAMERA");
    private static final List<String> LIMB_ENTRY_SIDES=List.of("NONE","FRAME_LEFT","FRAME_RIGHT","FRAME_TOP","FRAME_BOTTOM","INTO_DEPTH","OUT_OF_DEPTH");
    private static final Map<String,Integer> LENS_PRESETS=Map.of("LENS_18MM",18,"LENS_24MM",24,"LENS_28MM",28,"LENS_35MM",35,"LENS_50MM",50,"LENS_85MM",85,"LENS_135MM",135);
    private final StructuredJson json;
    private final ShotComplexityValidator complexity=new ShotComplexityValidator();
    private final DirectorPlanValidator planQuality=new DirectorPlanValidator();
    private final DirectorDurationPolicy durationPolicy=new DirectorDurationPolicy();
    public DirectorContract(ObjectMapper mapper, Validator validator) { json=new StructuredJson(mapper,validator); }

    /** Small first-stage contract: decide what to shoot, without repeating detailed state per shot. */
    public ObjectNode planSchema(JsonNode input) {
        JsonNode assets=input.path("assets");
        if(keys(assets,"locations").isEmpty())invalid("input.assets.locations","拆镜前需要本版故事的场景资产");
        int[] bounds=shotBounds(input);
        ObjectNode skeleton=object(),p=skeleton.withObject("properties");
        p.set("shotIndex",obj().put("type","integer").put("minimum",1).put("maximum",bounds[1]));
        p.set("beatId",string());p.set("purpose",boundedString(1,160));p.set("action",boundedString(1,180));
        p.set("subject",boundedString(1,120));p.set("secondarySubjects",array(boundedString(1,120),0).put("maxItems",6));
        p.set("dialogueOwner",values(withEmpty(keys(assets,"characters"))));
        p.set("duration",obj().put("type","number").put("minimum",2).put("maximum",5));
        p.set("shotSize",values(SHOT_SIZES));p.set("cameraAngle",values(CAMERA_ANGLES));p.set("cameraMovement",values(CAMERA_MOVEMENTS));
        p.set("relationToPrevious",values(sceneRelations()));p.set("directorIntent",values(DIRECTOR_INTENTS));p.set("transition",values(TRANSITIONS));
        p.set("characterIds",ids(assets,"characters"));p.set("propIds",ids(assets,"props"));p.set("basicBlocking",basicBlockingSchema(assets));requireAll(skeleton);
        ObjectNode root=object(),rp=root.withObject("properties");
        rp.set("directorPlan",directorPlanSchema());rp.set("dramaticBeats",array(planBeatSchema(assets),1).put("maxItems",bounds[1]));
        rp.set("sceneState",sceneStateSchema(assets));rp.set("sceneInitialState",stateSchema(assets));
        rp.set("shotSkeletons",array(skeleton,bounds[0]).put("maxItems",bounds[1]));requireAll(root);return root;
    }

    /** Small second-stage contract for one persisted batch of shot details. */
    public ObjectNode detailSchema(JsonNode input) {
        JsonNode assets=input.path("assets");int count=input.path("shotSkeletons").size();
        if(count!=1)invalid("input.shotSkeletons","每个详情请求必须且只能包含 1 个镜头骨架");
        ObjectNode detail=object(),p=detail.withObject("properties");
        p.set("shotIndex",obj().put("type","integer").put("minimum",1).put("maximum",1000));
        p.set("blocking",blockingDetailSchema(assets));p.set("performancePlan",performanceSchema());p.set("visibilityPlan",visibilitySchema());
        p.set("visualFocus",boundedString(1,160));p.set("emotion",boundedString(1,160));p.set("expression",boundedString(1,160));p.set("eyeLine",boundedString(1,160));p.set("focus",boundedString(1,160));
        p.set("difficulty",values(Arrays.stream(Difficulty.values()).map(Enum::name).toList()));p.set("cameraPlan",cameraDetailSchema());p.set("dialogues",dialogueDetailSchema(assets));
        p.set("referenceViews",detailReferenceViewsSchema(input));
        ObjectNode change=object(),cp=change.withObject("properties");
        cp.set("path",boundedString(1,240).put("pattern","^(characters\\.[^.]+\\.(lookId|position|lookDirection|holding|pose|actionState)|props\\.[^.]+\\.(holder|position|state))$"));
        cp.set("to",boundedString(0,240));cp.set("reason",boundedString(1,240));requireAll(change);
        p.set("stateChanges",array(change,0).put("maxItems",20));requireAll(detail);
        ObjectNode root=object();root.withObject("properties").set("shots",array(detail,count).put("maxItems",count));requireAll(root);return root;
    }

    private ObjectNode blockingDetailSchema(JsonNode assets){
        ObjectNode blocking=object(),p=blocking.withObject("properties"),actor=object(),a=actor.withObject("properties");
        a.set("characterId",values(keys(assets,"characters")));a.set("framePosition",boundedString(1,120));a.set("eyeLineTarget",boundedString(1,120));requireAll(actor);
        a.set("visibleBodyPart",values(VISIBLE_BODY_PARTS));a.set("bodyFrameSide",values(BODY_FRAME_SIDES));a.set("limbEntrySide",values(LIMB_ENTRY_SIDES));a.set("contactPoint",boundedString(0,160));requireAll(actor);
        p.set("characters",array(actor,0).put("maxItems",Math.max(1,keys(assets,"characters").size())));requireAll(blocking);return blocking;
    }

    private ObjectNode detailReferenceViewsSchema(JsonNode input){
        JsonNode skeleton=input.path("shotSkeletons").path(0);int characterCount=skeleton.path("characterIds").size(),propCount=skeleton.path("propIds").size();
        ObjectNode views=object(),p=views.withObject("properties");
        p.set("characterViews",array(values(List.of("FRONT","LEFT","RIGHT","BACK")),characterCount).put("maxItems",characterCount));
        p.set("locationView",values(LocationViewProjection.perspectiveViews()));
        p.set("propViews",array(values(List.of("FRONT","SIDE","BACK","SCALE")),propCount).put("maxItems",propCount));
        requireAll(views);return views;
    }

    private ObjectNode basicBlockingSchema(JsonNode assets){
        ObjectNode blocking=object(),p=blocking.withObject("properties");
        p.set("cameraWorldPosition",boundedString(1,160));p.set("axis",boundedString(1,160));
        p.set("axisSide",values(List.of("A_SIDE","B_SIDE","ON_AXIS")));p.set("axisChangeReason",boundedString(0,200));
        p.set("keyObjectPositions",boundedString(1,200));p.set("doorWindowState",boundedString(1,160));
        ObjectNode actor=object(),a=actor.withObject("properties");a.set("characterId",values(keys(assets,"characters")));
        for(String field:List.of("worldPosition","facing"))a.set(field,boundedString(1,120));
        a.set("screenDirection",values(SCREEN_DIRECTIONS));requireAll(actor);
        p.set("characters",array(actor,0).put("maxItems",Math.max(1,keys(assets,"characters").size())));requireAll(blocking);return blocking;
    }

    public ObjectNode schema(JsonNode input) {
        JsonNode assets=input.path("assets");
        if(keys(assets,"locations").isEmpty())invalid("input.assets.locations","拆镜前需要本版故事的场景资产");
        ObjectNode shot=object();ObjectNode p=(ObjectNode)shot.path("properties");
        for(String field:List.of("purpose","action","visualFocus","emotion"))p.set(field,string());
        p.set("beatId",string());p.set("directorIntent",values(DIRECTOR_INTENTS));
        p.set("shotPurpose",string());p.set("subject",string());p.set("secondarySubjects",array(string(),0).put("maxItems",8));
        p.set("blocking",blockingSchema(assets));p.set("performancePlan",performanceSchema());p.set("visibilityPlan",visibilitySchema());
        p.set("expression",string());p.set("eyeLine",string());p.set("focus",string());p.set("dialogueOwner",values(withEmpty(keys(assets,"characters"))));
        p.set("transition",values(TRANSITIONS));
        ((ObjectNode)p.path("action")).put("maxLength",180);
        p.set("duration",obj().put("type","number").put("minimum",2).put("maximum",5));
        p.set("shotSize",values(SHOT_SIZES));p.set("cameraAngle",values(CAMERA_ANGLES));p.set("cameraMovement",values(CAMERA_MOVEMENTS));
        p.set("relationToPrevious",values(sceneRelations()));
        p.set("difficulty",values(Arrays.stream(Difficulty.values()).map(Enum::name).toList()));
        p.set("cameraPlan",cameraSchema());
        p.set("characterIds",ids(assets,"characters"));p.set("propIds",ids(assets,"props"));
        p.set("dialogues",dialogueSchema(assets));
        p.set("startState",stateSchema(assets));p.set("endState",stateSchema(assets));
        ObjectNode change=object();ObjectNode changeProperties=change.withObject("properties");
        changeProperties.set("path",string().put("pattern","^(characters\\.[^.]+\\.(lookId|position|lookDirection|holding|pose|actionState)|props\\.[^.]+\\.(holder|position|state))$"));
        changeProperties.set("from",boundedString(0,240));changeProperties.set("to",boundedString(0,240));changeProperties.set("reason",string());requireAll(change);
        p.set("authorizedChanges",array(change,0).put("maxItems",20));
        ObjectNode views=object();ObjectNode viewProperties=views.withObject("properties");
        for(String id:keys(assets,"looks"))viewProperties.set(id,values(List.of("FRONT","LEFT","RIGHT","BACK")));
        for(String id:keys(assets,"locations"))viewProperties.set(id,values(LocationViewProjection.perspectiveViews()));
        for(String id:keys(assets,"props"))viewProperties.set(id,values(List.of("FRONT","SIDE","BACK","SCALE")));
        p.set("referenceViews",views);requireAll(shot);
        int[] bounds=shotBounds(input);
        ObjectNode root=object();root.withObject("properties").set("directorPlan",directorPlanSchema());
        root.withObject("properties").set("dramaticBeats",array(beatSchema(assets),1).put("maxItems",bounds[1]));
        root.withObject("properties").set("sceneState",sceneStateSchema(assets));
        root.withObject("properties").set("shots",array(shot,bounds[0]).put("maxItems",bounds[1]));requireAll(root);return root;
    }

    private ObjectNode directorPlanSchema(){
        ObjectNode plan=object(),p=plan.withObject("properties");
        p.set("planPurpose",string());p.set("scenePacing",values(List.of("SLOW","NORMAL","FAST","TENSION_BUILD","CLIMAX")));
        p.set("shotRepetitionReason",obj().put("type","string").put("maxLength",300));p.set("cameraStrategy",string());
        ObjectNode style=object(),s=style.withObject("properties");
        s.set("visualRhythm",string());s.set("averageShotLength",obj().put("type","number").put("minimum",1).put("maximum",8));
        s.set("cameraActivity",values(List.of("LOW","BALANCED","HIGH")));s.set("closeUpPreference",obj().put("type","number").put("minimum",0).put("maximum",1));
        s.set("reactionShotPreference",obj().put("type","number").put("minimum",0).put("maximum",1));s.set("compositionStyle",string());s.set("tensionStyle",string());requireAll(style);
        p.set("styleProfile",style);requireAll(plan);return plan;
    }
    private ObjectNode beatSchema(JsonNode assets){
        ObjectNode beat=object(),p=beat.withObject("properties");
        for(String field:List.of("beatId","beatPurpose","action","conflict","emotionBefore","emotionAfter","informationReveal"))p.set(field,string());
        p.set("activeCharacters",ids(assets,"characters"));p.set("storyFactChanges",array(string(),0).put("maxItems",12));p.set("relationshipChanges",array(string(),0).put("maxItems",12));
        p.set("importance",values(List.of("LOW","MEDIUM","HIGH","CLIMAX")));p.set("suggestedDuration",obj().put("type","number").put("minimum",1).put("maximum",120));requireAll(beat);return beat;
    }
    private ObjectNode planBeatSchema(JsonNode assets){
        ObjectNode beat=beatSchema(assets);ArrayNode required=JsonNodeFactory.instance.arrayNode();for(JsonNode field:beat.path("required"))if(!"activeCharacters".equals(field.asText()))required.add(field.asText());beat.set("required",required);return beat;
    }
    private ObjectNode blockingSchema(JsonNode assets){
        ObjectNode blocking=object(),p=blocking.withObject("properties");
        for(String field:List.of("cameraWorldPosition","axis","keyObjectPositions","doorWindowState"))p.set(field,string());
        p.set("axisSide",values(List.of("A_SIDE","B_SIDE","ON_AXIS")));p.set("axisChangeReason",obj().put("type","string").put("maxLength",240));
        ObjectNode actor=object(),a=actor.withObject("properties");a.set("characterId",values(keys(assets,"characters")));
        for(String field:List.of("worldPosition","facing","framePosition","eyeLineTarget"))a.set(field,string());
        a.set("screenDirection",values(SCREEN_DIRECTIONS));a.set("visibleBodyPart",values(VISIBLE_BODY_PARTS));a.set("bodyFrameSide",values(BODY_FRAME_SIDES));a.set("limbEntrySide",values(LIMB_ENTRY_SIDES));a.set("contactPoint",boundedString(0,160));requireAll(actor);
        p.set("characters",array(actor,0).put("maxItems",Math.max(1,keys(assets,"characters").size())));requireAll(blocking);return blocking;
    }
    private ObjectNode performanceSchema(){
        ObjectNode performance=object(),p=performance.withObject("properties");
        for(String field:List.of("primaryAction","microExpression","bodyLanguage","gaze","gesture"))p.set(field,string());
        p.set("actionUnits",obj().put("type","integer").put("minimum",1).put("maximum",6));p.set("propOperations",array(string(),0).put("maxItems",6));requireAll(performance);return performance;
    }
    private ObjectNode visibilitySchema(){
        ObjectNode visibility=object(),p=visibility.withObject("properties");p.set("occlusion",values(List.of("NONE","PARTIAL","HEAVY")));
        p.set("requiredDetail",values(List.of("SILHOUETTE","ACTION","IDENTITY","FULL_BODY","PROP_DETAIL")));p.set("visibleFeatures",array(string(),0).put("maxItems",8));requireAll(visibility);return visibility;
    }

    private ObjectNode sceneStateSchema(JsonNode assets) {
        ObjectNode state=object();ObjectNode p=state.withObject("properties");
        p.set("locationId",values(keys(assets,"locations")));
        for(String field:List.of("time","lighting","spatialRelations"))p.set(field,string());
        requireAll(state);return state;
    }

    private ObjectNode cameraSchema() {
        ObjectNode camera=object();ObjectNode p=camera.withObject("properties");
        for(String field:List.of("position","height","distance","horizontalAngle","verticalAngle","subjectPlacement","focusPoint","depthOfField","lightingDirection","movementPath","movementSpeed"))p.set(field,string());
        p.set("lensMm",obj().put("type","number").put("minimum",14).put("maximum",200));requireAll(camera);return camera;
    }
    private ObjectNode cameraDetailSchema() {
        ObjectNode camera=object();ObjectNode p=camera.withObject("properties");
        for(String field:List.of("position","height","distance","horizontalAngle","verticalAngle","subjectPlacement","focusPoint","depthOfField","lightingDirection","movementPath","movementSpeed"))p.set(field,string());
        p.set("lensPreset",values(new ArrayList<>(LENS_PRESETS.keySet())));requireAll(camera);return camera;
    }
    private ObjectNode dialogueSchema(JsonNode assets) {
        ObjectNode line=object();ObjectNode p=line.withObject("properties");
        p.set("characterId",values(keys(assets,"characters")));
        for(String field:List.of("displayText","sourceScript","emotion"))p.set(field,string());
        ((ObjectNode)p.path("sourceScript")).put("maxLength",800);
        p.set("startMs",obj().put("type","integer").put("minimum",0));p.set("endMs",obj().put("type","integer").put("minimum",1));
        requireAll(line);ObjectNode result=array(line,0).put("maxItems",keys(assets,"characters").isEmpty()?0:4);return result;
    }
    private ObjectNode dialogueDetailSchema(JsonNode assets) {
        ObjectNode line=object();ObjectNode p=line.withObject("properties");
        p.set("characterId",values(keys(assets,"characters")));for(String field:List.of("displayText","emotion"))p.set(field,string());
        p.set("startMs",obj().put("type","integer").put("minimum",0));p.set("endMs",obj().put("type","integer").put("minimum",1));
        requireAll(line);return array(line,0).put("maxItems",keys(assets,"characters").isEmpty()?0:4);
    }
    private ObjectNode stateSchema(JsonNode assets) {
        ObjectNode state=object();ObjectNode p=state.withObject("properties");
        ObjectNode characters=object();ObjectNode charProperties=characters.withObject("properties");
        for(JsonNode actor:assets.path("characters")) {
            String actorId=id(actor);ObjectNode character=object();ObjectNode cp=character.withObject("properties");
            cp.set("identityId",obj().put("type","string").put("const",actorId));
            List<String> lookIds=new ArrayList<>();for(JsonNode look:assets.path("looks"))if(actorId.equals(text(look,"characterId")))lookIds.add(id(look));
            if(lookIds.isEmpty())invalid("input.assets.characters."+actorId+".lookId","拆镜前需要属于该人物的定妆版本");
            cp.set("lookId",values(lookIds));for(String field:List.of("position","lookDirection","pose","actionState"))cp.set(field,string());
            cp.set("holding",values(withEmpty(keys(assets,"props"))));requireAll(character);charProperties.set(actorId,character);
        }
        p.set("characters",characters);
        ObjectNode props=object();ObjectNode pp=props.withObject("properties");
        for(String propId:keys(assets,"props")) {
            ObjectNode prop=object();prop.withObject("properties").set("holder",values(withEmpty(keys(assets,"characters"))));
            prop.withObject("properties").set("position",string());prop.withObject("properties").set("state",string());requireAll(prop);pp.set(propId,prop);
        }
        p.set("props",props);requireAll(state);return state;
    }

    /** Keep project configuration authoritative while retaining the raw provider output on the job. */
    public ObjectNode canonicalizeTrustedFields(JsonNode output,JsonNode input) {
        if(!output.isObject())invalid("$","导演输出必须是 JSON 对象");
        ObjectNode canonical=((ObjectNode)output).deepCopy();
        if(input.path("directorStyleProfile").isObject())canonical.withObject("directorPlan").set("styleProfile",input.path("directorStyleProfile").deepCopy());
        Set<String> allowedFacts=strings(input.path("scene").path("storyFactChanges"));
        Set<String> allowedRelationships=strings(input.path("scene").path("relationshipChanges"));
        for(JsonNode value:canonical.path("dramaticBeats"))if(value.isObject()){
            retainAuthorized((ObjectNode)value,"storyFactChanges",allowedFacts);
            retainAuthorized((ObjectNode)value,"relationshipChanges",allowedRelationships);
        }
        for(JsonNode value:canonical.path("shots"))if(value.isObject())pruneOffscreenState((ObjectNode)value);
        return canonical;
    }

    public ObjectNode canonicalizePlan(JsonNode output,JsonNode input){
        if(!output.isObject())invalid("$","导演规划必须是 JSON 对象");
        ObjectNode canonical=((ObjectNode)output).deepCopy();
        if(input.path("directorStyleProfile").isObject())canonical.withObject("directorPlan").set("styleProfile",input.path("directorStyleProfile").deepCopy());
        Set<String> allowedFacts=strings(input.path("scene").path("storyFactChanges"));
        Set<String> allowedRelationships=strings(input.path("scene").path("relationshipChanges"));
        Map<String,LinkedHashSet<String>> activeByBeat=new LinkedHashMap<>();
        for(JsonNode shot:canonical.path("shotSkeletons")){LinkedHashSet<String> active=activeByBeat.computeIfAbsent(text(shot,"beatId"),ignored->new LinkedHashSet<>());shot.path("characterIds").forEach(value->active.add(value.asText()));}
        JsonNode previousBeat=null;
        for(JsonNode beat:canonical.path("dramaticBeats"))if(beat.isObject()){
            retainAuthorized((ObjectNode)beat,"storyFactChanges",allowedFacts);
            retainAuthorized((ObjectNode)beat,"relationshipChanges",allowedRelationships);
            if(canonical.has("shotSkeletons")){ArrayNode active=((ObjectNode)beat).putArray("activeCharacters");activeByBeat.getOrDefault(text(beat,"beatId"),new LinkedHashSet<>()).forEach(active::add);}
            if(previousBeat!=null&&!Collections.disjoint(strings(previousBeat.path("activeCharacters")),strings(beat.path("activeCharacters"))))
                ((ObjectNode)beat).put("emotionBefore",text(previousBeat,"emotionAfter"));
            previousBeat=beat;
        }
        return canonical;
    }

    public void validatePlan(JsonNode output,JsonNode input,String requestId){
        json.parse(output.toString(),planSchema(input),JsonNode.class,requestId);
        if(input.path("directorStyleProfile").isObject()&&!input.path("directorStyleProfile").equals(output.path("directorPlan").path("styleProfile")))invalid("$.directorPlan.styleProfile","必须使用服务端已确认的导演风格");
        Set<String> beatIds=new LinkedHashSet<>(),covered=new LinkedHashSet<>();int beatIndex=0;
        Set<String> allowedFacts=strings(input.path("scene").path("storyFactChanges")),allowedRelationships=strings(input.path("scene").path("relationshipChanges"));
        for(JsonNode beat:output.path("dramaticBeats")){
            if(!beatIds.add(text(beat,"beatId")))invalid("$.dramaticBeats["+beatIndex+"].beatId","剧情节拍 ID 不能重复");
            validateAuthorizedChanges(beat.path("storyFactChanges"),allowedFacts,"$.dramaticBeats["+beatIndex+"].storyFactChanges");
            validateAuthorizedChanges(beat.path("relationshipChanges"),allowedRelationships,"$.dramaticBeats["+beatIndex+"].relationshipChanges");beatIndex++;
        }
        ObjectNode all=obj();ArrayNode characters=all.putArray("characterIds"),props=all.putArray("propIds");
        keys(input.path("assets"),"characters").forEach(characters::add);keys(input.path("assets"),"props").forEach(props::add);
        validateState(all,output.path("sceneInitialState"),"$.sceneInitialState");
        double total=0;JsonNode previous=null;int index=0;
        for(JsonNode shot:output.path("shotSkeletons")){
            String path="$.shotSkeletons["+index+"]";
            if(shot.path("shotIndex").asInt()!=index+1)invalid(path+".shotIndex","镜头编号必须从 1 连续递增");
            if(!beatIds.contains(text(shot,"beatId")))invalid(path+".beatId","镜头必须引用本场已定义的剧情节拍");covered.add(text(shot,"beatId"));
            ShotRelation relation=ShotRelation.valueOf(text(shot,"relationToPrevious"));if(index==0&&relation!=ShotRelation.ESTABLISHING)invalid(path+".relationToPrevious","场景首镜必须为 ESTABLISHING");
            unique(shot.path("characterIds"),path+".characterIds");unique(shot.path("propIds"),path+".propIds");
            String owner=text(shot,"dialogueOwner");if(!owner.isBlank()&&!strings(shot.path("characterIds")).contains(owner))invalid(path+".dialogueOwner","对白人物必须在本镜头中");
            if(previous!=null)validateBasicSpatialContinuity(previous,shot,path);previous=shot;total+=shot.path("duration").asDouble();index++;
        }
        beatIndex=0;for(JsonNode beat:output.path("dramaticBeats")){if(!covered.contains(text(beat,"beatId")))invalid("$.dramaticBeats["+beatIndex+"].beatId","每个剧情节拍至少需要一个镜头承载");beatIndex++;}
        ObjectNode quality=obj();quality.set("directorPlan",output.path("directorPlan"));quality.set("dramaticBeats",output.path("dramaticBeats"));quality.set("shots",output.path("shotSkeletons"));
        List<ProductionModels.Risk> risks=planQuality.validate(quality);if(!risks.isEmpty())invalid("$."+risks.getFirst().path(),"REPLAN_SCENE / "+risks.getFirst().code()+"："+risks.getFirst().message());
        double expected=duration(input);if(Math.abs(total-expected)>0.01)invalid("$.shotSkeletons.duration","总时长 "+total+" 秒必须与场景 "+expected+" 秒一致");
    }

    private void validateBasicSpatialContinuity(JsonNode previous,JsonNode current,String path){
        JsonNode before=previous.path("basicBlocking"),after=current.path("basicBlocking");
        if(!text(before,"axis").equals(text(after,"axis")))invalid(path+".basicBlocking.axis","同一场景的表演轴线不能在镜头间改写");
        String beforeSide=text(before,"axisSide"),afterSide=text(after,"axisSide");
        if(!beforeSide.equals(afterSide)&&!"ON_AXIS".equals(beforeSide)&&!"ON_AXIS".equals(afterSide)&&text(after,"axisChangeReason").isBlank())invalid(path+".basicBlocking.axisSide","跨越 180 度轴线必须说明原因");
    }

    /** Reconstruct full, replayable state snapshots from one batch of model deltas. */
    public ObjectNode reconstructBatch(JsonNode detailOutput,JsonNode batchInput,String requestId){
        json.parse(detailOutput.toString(),detailSchema(batchInput),JsonNode.class,requestId);
        ObjectNode ledger=requireObject(batchInput.path("currentState"),"input.currentState").deepCopy();
        ArrayNode shots=JsonNodeFactory.instance.arrayNode();JsonNode previous=batchInput.path("previousShotContinuity");
        for(int index=0;index<batchInput.path("shotSkeletons").size();index++){
            JsonNode skeleton=batchInput.path("shotSkeletons").path(index),detail=detailOutput.path("shots").path(index);String path="$.shots["+index+"]";
            if(detail.path("shotIndex").asInt()!=skeleton.path("shotIndex").asInt())invalid(path+".shotIndex","详情必须与本批镜头骨架逐项对应");
            ObjectNode start=ledger.deepCopy(),end=ledger.deepCopy();ArrayNode authorized=JsonNodeFactory.instance.arrayNode();
            for(JsonNode change:detail.path("stateChanges")){String from=applyChange(end,change,path+".stateChanges");authorized.add(obj().put("path",text(change,"path")).put("from",from).put("to",text(change,"to")).put("reason",text(change,"reason")));}
            ObjectNode shot=obj();for(String field:List.of("purpose","action","beatId","directorIntent","subject","secondarySubjects","dialogueOwner","transition","duration","shotSize","cameraAngle","cameraMovement","relationToPrevious","characterIds","propIds"))shot.set(field,skeleton.path(field).deepCopy());
            shot.put("shotPurpose",text(skeleton,"purpose"));
            shot.set("blocking",materializeBlocking(skeleton,detail.path("blocking"),start,path));
            for(String field:List.of("performancePlan","visibilityPlan","visualFocus","emotion","expression","eyeLine","focus","difficulty"))shot.set(field,detail.path(field).deepCopy());
            shot.set("cameraPlan",materializeCameraPlan(detail.path("cameraPlan"),path));
            shot.set("dialogues",materializeDialogues(detail.path("dialogues"),batchInput,path));
            shot.set("referenceViews",materializeReferenceViews(skeleton,detail.path("referenceViews"),start,batchInput.path("sceneState"),path));
            shot.set("authorizedChanges",authorized);shot.set("startState",start);shot.set("endState",end);
            validateState(shot,start,path+".startState");validateState(shot,end,path+".endState");
            validateViews(shot,batchInput.path("sceneState"),path);validateDialogues(shot,batchInput,path);
            if(previous.isObject()&&!previous.isEmpty())validateSpatialContinuity(previous,shot,path);
            List<ProductionModels.Risk> complexityRisks=complexity.validate(shot);if(!complexityRisks.isEmpty())invalid(path+"."+complexityRisks.getFirst().path(),"REPLAN_SHOT / "+complexityRisks.getFirst().code()+"："+complexityRisks.getFirst().message());
            for(JsonNode actor:shot.path("characterIds"))if(!start.path("characters").path(actor.asText()).path("lookId").equals(end.path("characters").path(actor.asText()).path("lookId")))invalid(path+".endState.characters."+actor.asText()+".lookId","单镜内定妆不能变化");
            shots.add(shot);ledger=end;previous=shot;
        }
        ObjectNode last=obj();if(!shots.isEmpty()){JsonNode shot=shots.get(shots.size()-1);for(String field:List.of("blocking","cameraPlan","emotion","eyeLine","relationToPrevious"))last.set(field,shot.path(field).deepCopy());last.put("shotIndex",batchInput.path("shotSkeletons").path(batchInput.path("shotSkeletons").size()-1).path("shotIndex").asInt());}
        ObjectNode result=obj();result.set("shots",shots);result.set("finalContinuity",ledger);result.set("lastShotContinuity",last);return result;
    }

    private ObjectNode materializeReferenceViews(JsonNode skeleton,JsonNode selected,JsonNode start,JsonNode sceneState,String path){
        JsonNode characterViews=selected.path("characterViews"),propViews=selected.path("propViews");
        if(characterViews.size()!=skeleton.path("characterIds").size())invalid(path+".referenceViews.characterViews","须按 characterIds 顺序逐项选择视角");
        if(propViews.size()!=skeleton.path("propIds").size())invalid(path+".referenceViews.propViews","须按 propIds 顺序逐项选择视角");
        ObjectNode result=obj();
        for(int i=0;i<characterViews.size();i++){
            String actorId=skeleton.path("characterIds").path(i).asText(),lookId=text(start.path("characters").path(actorId),"lookId");
            if(lookId.isBlank())invalid(path+".referenceViews.characterViews["+i+"]","当前人物没有已确认定妆");
            result.put(lookId,characterViews.path(i).asText());
        }
        String locationId=text(sceneState,"locationId");if(locationId.isBlank())invalid(path+".referenceViews.locationView","当前场景没有已确认地点");
        result.put(locationId,text(selected,"locationView"));
        for(int i=0;i<propViews.size();i++)result.put(skeleton.path("propIds").path(i).asText(),propViews.path(i).asText());
        return result;
    }

    private ArrayNode materializeDialogues(JsonNode selected,JsonNode input,String path){
        String script=text(input.path("episodeScript"),"script");ArrayNode result=JsonNodeFactory.instance.arrayNode();int index=0;
        for(JsonNode value:selected){String linePath=path+".dialogues["+index+++ "]",display=text(value,"displayText");
            if(display.isBlank()||script.isBlank()||!script.contains(display))invalid(linePath+".displayText","台词必须逐字摘自已确认的单集剧本");
            String source=Arrays.stream(script.split("\\R",-1)).filter(line->line.contains(display)).findFirst().orElse(display);
            ObjectNode materialized=((ObjectNode)value).deepCopy();materialized.put("sourceScript",source);result.add(materialized);
        }
        return result;
    }

    private ObjectNode materializeCameraPlan(JsonNode selected,String path){
        String preset=text(selected,"lensPreset");Integer lens=LENS_PRESETS.get(preset);if(lens==null)invalid(path+".cameraPlan.lensPreset","必须选择固定焦段");
        ObjectNode result=((ObjectNode)selected).deepCopy();result.remove("lensPreset");result.put("lensMm",lens);return result;
    }

    private String applyChange(ObjectNode state,JsonNode change,String path){
        String[] parts=text(change,"path").split("\\.");if(parts.length!=3)invalid(path+".path","状态路径格式无效");
        JsonNode entity=state.path(parts[0]).path(parts[1]);if(!entity.isObject()||!entity.has(parts[2]))invalid(path+".path","状态路径不存在于场景初始状态");
        String current=entity.path(parts[2]).asText();((ObjectNode)entity).put(parts[2],text(change,"to"));return current;
    }

    private ObjectNode materializeBlocking(JsonNode skeleton,JsonNode framing,JsonNode start,String path){
        ObjectNode blocking=requireObject(skeleton.path("basicBlocking"),path+".basicBlocking").deepCopy();
        Set<String> expected=strings(skeleton.path("characterIds")),actual=new LinkedHashSet<>();Map<String,JsonNode> framingById=new LinkedHashMap<>();
        for(JsonNode actor:framing.path("characters")){String actorId=text(actor,"characterId");if(!actual.add(actorId))invalid(path+".blocking.characters","人物构图不能重复");framingById.put(actorId,actor);}
        if(!actual.equals(expected))invalid(path+".blocking.characters","必须且只能为本镜可见人物提供画面位置和视线目标");
        Map<String,JsonNode> plannedById=new LinkedHashMap<>();for(JsonNode actor:blocking.path("characters"))plannedById.put(text(actor,"characterId"),actor);
        ArrayNode actors=JsonNodeFactory.instance.arrayNode();for(String actorId:expected){JsonNode planned=plannedById.get(actorId),state=start.path("characters").path(actorId),frame=framingById.get(actorId);if(planned==null||!state.isObject())invalid(path+".blocking.characters."+actorId,"缺少人物规划或连续性起始状态");ObjectNode actor=((ObjectNode)planned).deepCopy();actor.put("worldPosition",text(state,"position"));if(!text(state,"lookDirection").isBlank())actor.put("facing",text(state,"lookDirection"));for(String field:List.of("framePosition","eyeLineTarget","visibleBodyPart","bodyFrameSide","limbEntrySide","contactPoint"))actor.put(field,text(frame,field));actors.add(actor);}
        blocking.set("characters",actors);return blocking;
    }

    private ObjectNode requireObject(JsonNode value,String path){if(!value.isObject())invalid(path,"必须是对象");return (ObjectNode)value;}

    private static void retainAuthorized(ObjectNode beat,String field,Set<String> allowed) {
        ArrayNode retained=JsonNodeFactory.instance.arrayNode();
        for(JsonNode value:beat.path(field))if(allowed.contains(value.asText()))retained.add(value.deepCopy());
        beat.set(field,retained);
    }

    private static void pruneOffscreenState(ObjectNode shot) {
        Set<String> visibleCharacters=strings(shot.path("characterIds")),visibleProps=strings(shot.path("propIds"));
        for(String phase:List.of("startState","endState"))if(shot.path(phase).isObject()){
            if(shot.path(phase).path("characters").isObject())retainObjectKeys((ObjectNode)shot.path(phase).path("characters"),visibleCharacters);
            if(shot.path(phase).path("props").isObject())retainObjectKeys((ObjectNode)shot.path(phase).path("props"),visibleProps);
        }
        if(shot.path("blocking").path("characters").isArray()){
            ArrayNode retained=JsonNodeFactory.instance.arrayNode();
            for(JsonNode actor:shot.path("blocking").path("characters"))if(visibleCharacters.contains(text(actor,"characterId")))retained.add(actor.deepCopy());
            ((ObjectNode)shot.path("blocking")).set("characters",retained);
        }
    }

    private static void retainObjectKeys(ObjectNode object,Set<String> allowed) {
        List<String> remove=new ArrayList<>();object.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))remove.add(key);});remove.forEach(object::remove);
    }

    public void validate(JsonNode output,JsonNode input,String requestId) {
        if(output.path("shots").isArray()&&!output.path("shots").isEmpty()){
            double rawTotal=0;boolean numeric=true;for(JsonNode shot:output.path("shots")){numeric&=shot.path("duration").isNumber();rawTotal+=shot.path("duration").asDouble();}
            if(numeric&&Math.abs(rawTotal-duration(input))>0.01)invalid("$.shots.duration","总时长 "+rawTotal+" 秒必须与场景 "+duration(input)+" 秒一致");
        }
        json.parse(output.toString(),schema(input),JsonNode.class,requestId);
        if(input.path("directorStyleProfile").isObject()&&!input.path("directorStyleProfile").equals(output.path("directorPlan").path("styleProfile")))invalid("$.directorPlan.styleProfile","必须逐项使用项目与场景合并后的导演风格配置");
        Set<String> beatIds=new LinkedHashSet<>(),coveredBeats=new LinkedHashSet<>(),allowedFactChanges=strings(input.path("scene").path("storyFactChanges")),allowedRelationshipChanges=strings(input.path("scene").path("relationshipChanges"));int beatIndex=0;
        for(JsonNode beat:output.path("dramaticBeats")){
            String beatId=text(beat,"beatId");if(!beatIds.add(beatId))invalid("$.dramaticBeats["+beatIndex+"].beatId","剧情节拍 ID 不能重复");beatIndex++;
            validateAuthorizedChanges(beat.path("storyFactChanges"),allowedFactChanges,"$.dramaticBeats["+(beatIndex-1)+"].storyFactChanges");
            validateAuthorizedChanges(beat.path("relationshipChanges"),allowedRelationshipChanges,"$.dramaticBeats["+(beatIndex-1)+"].relationshipChanges");
        }
        ObjectNode ledger=initialLedger(input);
        double total=0;int index=0;JsonNode previousShotNode=null;
        for(JsonNode shot:output.path("shots")) {
            String path="$.shots["+index+"]";ShotRelation relation=ShotRelation.valueOf(text(shot,"relationToPrevious"));
            String beatId=text(shot,"beatId");if(!beatIds.contains(beatId))invalid(path+".beatId","镜头必须引用本场已定义的剧情节拍");coveredBeats.add(beatId);
            if(previousShotNode!=null)validateSpatialContinuity(previousShotNode,shot,path);
            if(index==0&&relation!=ShotRelation.ESTABLISHING)invalid(path+".relationToPrevious","场景首镜必须为 ESTABLISHING");
            total+=shot.path("duration").asDouble();
            for(String field:List.of("characterIds","propIds"))unique(shot.path(field),path+"."+field);
            validateState(shot,shot.path("startState"),path+".startState");validateState(shot,shot.path("endState"),path+".endState");
            validateViews(shot,output.path("sceneState"),path);
            validateDialogues(shot,input,path);
            // The model may omit off-screen assets to keep the request small.  The
            // server-side ledger retains their last confirmed state; compare only
            // fields explicitly reintroduced by this shot against that ledger.
            if(index>0)compare(ledger,shot.path("startState"),"",path+".startState",relation,shot.path("authorizedChanges"));
            List<ProductionModels.Risk> complexityRisks=complexity.validate(shot);if(!complexityRisks.isEmpty())invalid(path+"."+complexityRisks.getFirst().path(),"REPLAN_SHOT / "+complexityRisks.getFirst().code()+"："+complexityRisks.getFirst().message());
            for(JsonNode actor:shot.path("characterIds")) {
                String actorId=actor.asText();JsonNode start=shot.path("startState").path("characters").path(actorId),end=shot.path("endState").path("characters").path(actorId);
                if(!start.path("lookId").equals(end.path("lookId")))invalid(path+".endState.characters."+actorId+".lookId","单镜内定妆不能变化，请拆成有明确换装原因的新镜头");
            }
            merge(ledger,shot.path("endState"));
            previousShotNode=shot;index++;
        }
        beatIndex=0;for(JsonNode beat:output.path("dramaticBeats")){if(!coveredBeats.contains(text(beat,"beatId")))invalid("$.dramaticBeats["+beatIndex+"].beatId","每个剧情节拍至少需要一个镜头承载");beatIndex++;}
        List<ProductionModels.Risk> planRisks=planQuality.validate(output);if(!planRisks.isEmpty())invalid("$."+planRisks.getFirst().path(),"REPLAN_SCENE / "+planRisks.getFirst().code()+"："+planRisks.getFirst().message());
        double expected=duration(input);
        if(Math.abs(total-expected)>0.01)invalid("$.shots.duration","总时长 "+total+" 秒必须与场景 "+expected+" 秒一致");
    }
    private void validateAuthorizedChanges(JsonNode changes,Set<String> allowed,String path){for(JsonNode change:changes)if(!allowed.contains(change.asText()))invalid(path,"导演只能引用上游剧本已经声明的变化，不能修改 Story Truth");}
    private void validateSpatialContinuity(JsonNode previous,JsonNode current,String path){
        JsonNode before=previous.path("blocking"),after=current.path("blocking");
        if(!text(before,"axis").equals(text(after,"axis")))invalid(path+".blocking.axis","同一场景的表演轴线不能在镜头间改写");
        String beforeSide=text(before,"axisSide"),afterSide=text(after,"axisSide");
        if(!beforeSide.equals(afterSide)&&!"ON_AXIS".equals(beforeSide)&&!"ON_AXIS".equals(afterSide)&&text(after,"axisChangeReason").isBlank())
            invalid(path+".blocking.axisSide","跨越 180 度轴线必须说明剧情和调度理由");
        if("CONTINUOUS".equals(text(current,"relationToPrevious"))){
            Map<String,String> directions=new HashMap<>();for(JsonNode actor:before.path("characters"))directions.put(text(actor,"characterId"),text(actor,"screenDirection"));
            for(JsonNode actor:after.path("characters")){String id=text(actor,"characterId"),old=directions.get(id),now=text(actor,"screenDirection");if(old!=null&&oppositeScreenDirections(old,now))invalid(path+".blocking.characters."+id+".screenDirection","连续动作不能无理由反转行进或视线方向");}
        }
    }
    private boolean oppositeScreenDirections(String before,String after){
        return ("FRAME_LEFT".equals(before)&&"FRAME_RIGHT".equals(after))||("FRAME_RIGHT".equals(before)&&"FRAME_LEFT".equals(after))||
            ("INTO_DEPTH".equals(before)&&"OUT_OF_DEPTH".equals(after))||("OUT_OF_DEPTH".equals(before)&&"INTO_DEPTH".equals(after));
    }
    /** Build complete persisted snapshots without mutating the provider response. */
    public ArrayNode materialize(JsonNode output,JsonNode input) {
        ObjectNode ledger=initialLedger(input);merge(ledger,output.path("sceneState"));
        Map<String,JsonNode> beats=new HashMap<>();for(JsonNode beat:output.path("dramaticBeats"))beats.put(text(beat,"beatId"),beat);
        ArrayNode shots=JsonNodeFactory.instance.arrayNode();
        for(JsonNode raw:output.path("shots")) {
            ObjectNode start=ledger.deepCopy();merge(start,raw.path("startState"));
            ObjectNode end=start.deepCopy();merge(end,raw.path("endState"));
            ObjectNode shot=raw.deepCopy();shot.put("locationId",text(output.path("sceneState"),"locationId"));
            DirectorDurationPolicy.DurationPlan timing=durationPolicy.recommend(raw,beats.getOrDefault(text(raw,"beatId"),obj()),output.path("directorPlan"));
            double providerDuration=raw.path("duration").asDouble();shot.put("providerDuration",providerDuration).put("editDuration",Math.min(providerDuration,timing.editDuration())).put("durationBasis",timing.rationale());
            shot.set("startState",start);shot.set("endState",end);shots.add(shot);ledger=end;
        }
        return shots;
    }
    private ObjectNode initialLedger(JsonNode input) {
        ObjectNode ledger=obj();JsonNode state=input.path("scene").path("state");
        for(String field:List.of("characters","props"))if(state.path(field).isObject())ledger.set(field,state.path(field).deepCopy());
        return ledger;
    }
    private void validateState(JsonNode shot,JsonNode state,String path) {
        for(JsonNode actor:shot.path("characterIds"))if(!state.path("characters").has(actor.asText()))invalid(path+".characters."+actor.asText(),"可见人物缺少连续性状态");
        for(JsonNode prop:shot.path("propIds"))if(!state.path("props").has(prop.asText()))invalid(path+".props."+prop.asText(),"可见道具缺少归属和状态");
        state.path("characters").fields().forEachRemaining(e->{
            String holding=text(e.getValue(),"holding");if(!holding.isEmpty()&&!e.getKey().equals(text(state.path("props").path(holding),"holder")))invalid(path+".characters."+e.getKey()+".holding","与道具 holder 不一致");
        });
        state.path("props").fields().forEachRemaining(e->{
            String holder=text(e.getValue(),"holder");if(!holder.isEmpty()&&!e.getKey().equals(text(state.path("characters").path(holder),"holding")))invalid(path+".props."+e.getKey()+".holder","与人物 holding 不一致，持物者须在状态中");
        });
    }
    private void validateViews(JsonNode shot,JsonNode sceneState,String path) {
        Set<String> required=new LinkedHashSet<>();required.add(text(sceneState,"locationId"));
        for(JsonNode actor:shot.path("characterIds"))required.add(text(shot.path("startState").path("characters").path(actor.asText()),"lookId"));
        for(JsonNode prop:shot.path("propIds"))required.add(prop.asText());
        Set<String> actual=new HashSet<>();shot.path("referenceViews").fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(required))invalid(path+".referenceViews","必须且只能引用当前可见人物定妆、场景、道具各一个视角");
    }
    private void validateDialogues(JsonNode shot,JsonNode input,String path) {
        Set<String> visible=new HashSet<>();shot.path("characterIds").forEach(v->visible.add(v.asText()));
        String script=text(input.path("episodeScript"),"script");long previousEnd=0;int index=0;Set<String> owners=new LinkedHashSet<>();
        for(JsonNode line:shot.path("dialogues")) {
            String linePath=path+".dialogues["+index+++"]";
            String owner=text(line,"characterId");owners.add(owner);if(!visible.contains(owner))invalid(linePath+".characterId","对白人物必须出现在本镜头中");
            String source=text(line,"sourceScript"),display=text(line,"displayText");
            if(script.isBlank()||!script.contains(source)||!source.contains(display))invalid(linePath+".sourceScript","台词必须原样摘自已确认的单集剧本，不得在拆镜时改写或发明对白");
            long start=line.path("startMs").asLong(),end=line.path("endMs").asLong();
            if(start<previousEnd||end<=start||end>Math.round(shot.path("duration").asDouble()*1000))invalid(linePath+".startMs","对白时间必须顺序排列且落在本镜头时长内");
            previousEnd=end;
        }
        String declared=text(shot,"dialogueOwner");
        if(owners.size()>1)invalid(path+".dialogueOwner","一个原子镜头只能有一位对白说话者；交叉对白请拆镜");
        if(owners.isEmpty()&&!declared.isBlank())invalid(path+".dialogueOwner","无对白镜头不能声明说话者");
        if(!owners.isEmpty()&&!owners.contains(declared))invalid(path+".dialogueOwner","必须与本镜头对白说话者一致");
        if("SHOW_REACTION".equals(text(shot,"directorIntent"))&&!owners.isEmpty()){
            String subjectId=resolveCharacterSubject(text(shot,"subject"),input);
            if(!visible.contains(subjectId)||subjectId.equals(declared))invalid(path+".subject","对白反应镜头必须聚焦在场且不同于说话者的听者");
        }
    }
    private static String resolveCharacterSubject(String subject,JsonNode input) {
        String resolved="";
        for(JsonNode actor:input.path("assets").path("characters"))if(subject.equals(id(actor))||subject.equals(text(actor,"name"))||subject.equals(text(actor,"characterKey"))){
            if(!resolved.isBlank()&&!resolved.equals(id(actor)))return "";resolved=id(actor);
        }
        return resolved;
    }
    private void compare(JsonNode previous,JsonNode start,String relative,String path,ShotRelation relation,JsonNode changes) {
        start.fields().forEachRemaining(e->{
            if(!previous.has(e.getKey()))return;String field=relative.isEmpty()?e.getKey():relative+"."+e.getKey();JsonNode expected=previous.path(e.getKey());
            if(expected.isObject()&&e.getValue().isObject()) { compare(expected,e.getValue(),field,path+"."+e.getKey(),relation,changes);return; }
            boolean identity=e.getKey().equals("identityId");
            boolean inherited=ContinuityStatePolicy.inherits(field,relation);
            if(inherited&&!expected.equals(e.getValue())) {
                boolean authorized=false;for(JsonNode change:changes)if(field.equals(text(change,"path"))&&!text(change,"reason").isBlank())authorized=true;
                if(identity||!authorized)invalid(path+"."+e.getKey(),identity?"人物身份不能变化":"与上一镜结束状态冲突，必须有剧本支持的 authorizedChanges 原因");
            }
        });
    }
    /** Recursively retain confirmed off-screen state while applying this shot's state delta. */
    private void merge(ObjectNode ledger, JsonNode delta) {
        if(!delta.isObject())return;
        delta.fields().forEachRemaining(entry -> {
            JsonNode value=entry.getValue();
            JsonNode existing=ledger.get(entry.getKey());
            if(value.isObject() && existing!=null && existing.isObject()) merge((ObjectNode)existing,value);
            else ledger.set(entry.getKey(),value.deepCopy());
        });
    }
    private static void invalid(String path,String message){throw new IllegalArgumentException(path+"："+message);}
    private static void unique(JsonNode ids,String path){Set<String> seen=new HashSet<>();for(JsonNode value:ids)if(!seen.add(value.asText()))invalid(path,"不能重复引用同一资产");}
    private static List<String> sceneRelations(){return Arrays.stream(ShotRelation.values()).filter(value->value!=ShotRelation.TIME_JUMP&&value!=ShotRelation.LOCATION_CHANGE).map(Enum::name).toList();}
    private static List<String> keys(JsonNode root,String name){List<String> result=new ArrayList<>();for(JsonNode entry:root.path(name))result.add(id(entry));return result;}
    private static List<String> withEmpty(List<String> ids){List<String> result=new ArrayList<>(ids);result.add("");return result;}
    private static Set<String> strings(JsonNode values){Set<String> result=new LinkedHashSet<>();if(values.isArray())values.forEach(value->result.add(value.asText()));return result;}
    private static ObjectNode object(){ObjectNode result=obj().put("type","object").put("additionalProperties",false);result.putObject("properties");result.putArray("required");return result;}
    private static ObjectNode string(){return obj().put("type","string").put("minLength",1).put("maxLength",240);}
    private static ObjectNode boundedString(int min,int max){return obj().put("type","string").put("minLength",min).put("maxLength",max);}
    private static ObjectNode values(List<String> values){ObjectNode result=obj().put("type","string");ArrayNode options=result.putArray("enum");values.forEach(options::add);return result;}
    private static ObjectNode array(JsonNode item,int min){ObjectNode result=obj().put("type","array").put("minItems",min);result.set("items",item);return result;}
    private static ObjectNode ids(JsonNode root,String name){List<String> values=keys(root,name);ObjectNode result=array(values.isEmpty()?string():values(values),0);result.put("maxItems",values.size());return result;}
    private static void requireAll(ObjectNode object){ArrayNode required=object.withArray("required");object.path("properties").fieldNames().forEachRemaining(required::add);}
    private static int[] shotBounds(JsonNode input){
        double duration=duration(input);
        double average=input.path("directorStyleProfile").path("averageShotLength").asDouble(3);
        if(average<2||average>5)average=3;
        int target=Math.max(1,(int)Math.round(duration/average));
        int physicalMin=Math.max(1,(int)Math.ceil(duration/5d)),physicalMax=Math.max(physicalMin,(int)Math.floor(duration/2d));
        int min=Math.min(physicalMax,Math.max(physicalMin,(int)Math.ceil(target*.8)));
        int max=Math.max(min,Math.min(physicalMax,(int)Math.ceil(target*1.25)));
        return new int[]{min,max};
    }
    private static double duration(JsonNode input){return input.path("sceneTargetDurationSeconds").asDouble(input.path("scene").path("duration").asDouble(1));}
}
