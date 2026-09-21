package com.yourapp.drama.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.DirectorContract;
import com.yourapp.drama.production.ScriptBeatExtractor;
import com.yourapp.drama.production.AssetDependencyAnalyzer;
import com.yourapp.drama.production.SpatialComplexityAnalyzer;
import com.yourapp.drama.production.RulePackAssembler;
import com.yourapp.drama.production.RulePackBudgeter;
import com.yourapp.drama.production.RulePackResolver;
import com.yourapp.drama.production.RuntimeRulePackLoader;
import com.yourapp.drama.workflow.StoryDevelopmentService;
import com.yourapp.drama.workflow.StudioService;
import com.yourapp.drama.workflow.WorkflowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Persisted two-stage director pipeline: one plan followed by restartable detail batches. */
@Service
public class DirectorGenerationService {
    private static final int DETAIL_BATCH_SIZE=1;
    private final DocumentStore store;private final JobService jobs;private final StudioService studio;private final LlmGateway llm;
    private final ObjectMapper mapper;private final StoryDevelopmentService development;private final DirectorContract contract;private final boolean simulated;
    private final ScriptBeatExtractor scriptBeatExtractor=new ScriptBeatExtractor();
    private final RulePackResolver rulePackResolver=new RulePackResolver(new RuntimeRulePackLoader());
    private final RulePackAssembler rulePackAssembler=new RulePackAssembler();
    private final RulePackBudgeter rulePackBudgeter=new RulePackBudgeter();

    public DirectorGenerationService(DocumentStore store,JobService jobs,StudioService studio,LlmGateway llm,ObjectMapper mapper,
                                     StoryDevelopmentService development,DirectorContract contract,@Value("${drama.director.mode:${drama.provider.mode:mock}}")String mode){
        this.store=store;this.jobs=jobs;this.studio=studio;this.llm=llm;this.mapper=mapper;this.development=development;this.contract=contract;this.simulated="mock".equals(mode);
    }

    public void process(ObjectNode job){
        switch(text(job,"type")){case "DIRECTOR_PLAN"->plan(job);case "SHOT_DETAIL"->detail(job);default->throw new WorkflowException("UNSUPPORTED_JOB","未知导演任务："+text(job,"type"));}
    }

    private void plan(ObjectNode job){
        development.checkProductionInput(job);requireCurrent(job);activateRevalidatedPlan(job);
        ObjectNode source=(ObjectNode)job.path("inputSnapshot"),providerInput=planInput(source);DirectorRuleSelection selectedRules=directorRules(providerInput);providerInput.set("directorRuleProfile",selectedRules.profile().deepCopy());JsonNode schema=contract.planSchema(providerInput);
        auditRules(job,selectedRules);
        JsonNode generated=simulated?demoPlan(providerInput):generate(job,"DIRECTOR_PLAN",providerInput,schema);
        ObjectNode output=contract.canonicalizePlan(generated,providerInput);
        contract.validatePlan(output,providerInput,text(store.get(GENERATION_JOB,id(job)),"providerRequestId"));
        int count=output.path("shotSkeletons").size(),batchCount=(count+DETAIL_BATCH_SIZE-1)/DETAIL_BATCH_SIZE;
        ObjectNode result=obj().put("stage","DIRECTOR_PLAN").put("batchCount",batchCount).put("shotCount",count).put("simulated",simulated)
                .put("providerInputChars",providerInput.toString().length()).put("schemaChars",schema.toString().length());
        result.set("directorRuleProfile",selectedRules.profile().deepCopy());
        for(String field:List.of("directorPlan","dramaticBeats","sceneState","sceneInitialState","presenceLedger","shotSkeletons"))result.set(field,output.path(field).deepCopy());
        store.transaction(()->{requireCurrent(job);activateRevalidatedPlan(job);jobs.succeed(id(job),result);enqueueBatch(job,result,1,result.path("sceneInitialState"),obj());return null;});
    }

    private void detail(ObjectNode job){
        development.checkProductionInput(job);requireCurrent(job);
        ObjectNode input=(ObjectNode)job.path("inputSnapshot"),providerInput=detailInput(input);DirectorRuleSelection selectedRules=directorRules(providerInput);JsonNode schema=contract.detailSchema(providerInput);auditRules(job,selectedRules);
        JsonNode generated=simulated?demoDetail(input):generate(job,"SHOT_DETAIL",providerInput,schema);
        ObjectNode reconstructed=contract.reconstructBatch(generated,input,text(store.get(GENERATION_JOB,id(job)),"providerRequestId"));
        ObjectNode result=reconstructed.deepCopy().put("stage","SHOT_DETAIL").put("batchIndex",input.path("batchIndex").asInt())
                .put("shotStart",input.path("shotStart").asInt()).put("shotEnd",input.path("shotEnd").asInt()).put("simulated",simulated)
                .put("providerInputChars",providerInput.toString().length()).put("schemaChars",schema.toString().length());
        int batch=input.path("batchIndex").asInt(),batchCount=input.path("batchCount").asInt();
        store.transaction(()->{
            requireCurrent(job);jobs.succeed(id(job),result);
            if(batch<batchCount){ObjectNode root=store.get(GENERATION_JOB,required(input,"rootPlanJobId"));enqueueBatch(root,root.path("outputSnapshot"),batch+1,result.path("finalContinuity"),result.path("lastShotContinuity"));}
            else finalizePlan(required(input,"rootPlanJobId"));return null;
        });
    }

    private JsonNode generate(ObjectNode job,String stage,ObjectNode input,JsonNode schema){
        DirectorRuleSelection selectedRules=directorRules(input);
        String system=prompt()+"\n\n以下为本场按空间、素材依赖和表演复杂度选择的导演规则正文：\n"+selectedRules.content()+"\n当前阶段："+stage+"。只完成该阶段 Schema 要求的内容。";
        jobs.mutate(id(job),j->j.put("generationStage",stage).put("systemPromptChars",system.length()).put("providerInputChars",input.toString().length()).put("schemaChars",schema.toString().length()));
        String retryOf=text(job.path("inputSnapshot"),"retryOfJobId");
        if(job.path("inputSnapshot").path("reuseProviderOutput").asBoolean(false)&&!retryOf.isBlank()){
            ObjectNode previous=store.get(GENERATION_JOB,retryOf);
            if(previous.path("providerOutput").isObject()&&!previous.path("providerOutput").isEmpty()){
                JsonNode recovered=previous.path("providerOutput").deepCopy();
                jobs.mutate(id(job),j->{j.put("providerRequestId",text(previous,"providerRequestId")).put("model",text(previous,"model")).put("simulated",false)
                    .put("finishReason",text(previous,"finishReason").isBlank()?"completed":text(previous,"finishReason")).put("reusedValidatedProviderOutput",true).put("recoveredFromJobId",retryOf);j.set("providerOutput",recovered.deepCopy());if(previous.path("providerUsage").isObject())j.set("providerUsage",previous.path("providerUsage").deepCopy());});
                return recovered;
            }
        }
        var request=new LlmGateway.StructuredRequest(system,input.toString(),mapper.convertValue(schema,new TypeReference<Map<String,Object>>(){}),Map.of("modelRole","director"));
        var result=llm.generate(request,JsonNode.class);
        jobs.mutate(id(job),j->{j.put("providerRequestId",result.requestId()).put("model",result.model()).put("simulated",result.simulated()).put("finishReason",result.usage().finishReason());
            j.set("providerUsage",usage(result.usage()));j.set("providerOutput",result.value().deepCopy());});return result.value();
    }

    private void enqueueBatch(ObjectNode root,JsonNode plan,int batchIndex,JsonNode currentState,JsonNode previous){
        int count=plan.path("shotSkeletons").size(),from=(batchIndex-1)*DETAIL_BATCH_SIZE,to=Math.min(count,from+DETAIL_BATCH_SIZE);
        ObjectNode source=(ObjectNode)root.path("inputSnapshot"),input=obj().put("rootPlanJobId",id(root)).put("batchIndex",batchIndex)
                .put("batchCount",(count+DETAIL_BATCH_SIZE-1)/DETAIL_BATCH_SIZE).put("shotStart",from+1).put("shotEnd",to)
                .put("sceneTargetDurationSeconds",source.path("sceneTargetDurationSeconds").asDouble()).put("planningSignature",required(source,"planningSignature"));
        for(String field:List.of("planVersion","directorPlanVersion","dramaticBeatVersion","shotPlanVersion"))input.set(field,source.path(field).deepCopy());
        input.set("scene",compact(source.path("scene"),List.of("id","name","description","storyFactChanges","relationshipChanges")));
        input.set("project",source.path("project").deepCopy());input.set("episodeFormat",source.path("episodeFormat").deepCopy());input.set("episodeScript",compact(source.path("episodeScript"),List.of("storyDocumentId","script","episodeFormatId","beatMode","beatBoundaries","midHook")));
        input.set("directorPlan",plan.path("directorPlan").deepCopy());input.set("sceneState",plan.path("sceneState").deepCopy());input.set("presenceLedger",plan.path("presenceLedger").deepCopy());
        input.set("currentState",currentState.deepCopy());input.set("previousShotContinuity",previous.deepCopy());input.set("assetViewIds",source.path("assetViewIds").deepCopy());
        input.set("directorRuleProfile",plan.path("directorRuleProfile").deepCopy());
        ArrayNode skeletons=input.putArray("shotSkeletons");Set<String> beatIds=new LinkedHashSet<>();for(int i=from;i<to;i++){JsonNode skeleton=plan.path("shotSkeletons").path(i);skeletons.add(skeleton.deepCopy());beatIds.add(text(skeleton,"beatId"));}
        ArrayNode beats=input.putArray("dramaticBeats");for(JsonNode beat:plan.path("dramaticBeats"))if(beatIds.contains(text(beat,"beatId")))beats.add(beat.deepCopy());
        input.set("assets",relevantAssets(source.path("assets"),skeletons,previous));
        jobs.enqueue(project(root),null,"SHOT_DETAIL",input,"shot-detail:"+id(root)+":"+batchIndex);
    }

    private ObjectNode planInput(JsonNode source){
        ObjectNode input=obj().put("stage","DIRECTOR_PLAN").put("sceneTargetDurationSeconds",source.path("sceneTargetDurationSeconds").asDouble());
        input.set("scene",compact(source.path("scene"),List.of("id","name","description","state","storyFactChanges","relationshipChanges")));
        input.set("project",source.path("project").deepCopy());input.set("episodeFormat",source.path("episodeFormat").deepCopy());input.set("directorStyleProfile",source.path("directorStyleProfile").deepCopy());
        if(source.path("directorRuleProfile").isObject())input.set("directorRuleProfile",source.path("directorRuleProfile").deepCopy());
        if(source.path("retryFeedback").isObject())input.set("retryFeedback",source.path("retryFeedback").deepCopy());
        ObjectNode episodeScript=compact(source.path("episodeScript"),List.of("script","episodeFormatId","beatMode","beatBoundaries","midHook","targetDurationSec"));
        if(!episodeScript.has("targetDurationSec"))episodeScript.put("targetDurationSec",source.path("sceneTargetDurationSeconds").asDouble());
        input.set("episodeScript",episodeScript);input.set("scriptBeats",scriptBeatExtractor.extract(episodeScript));input.set("assets",source.path("assets").deepCopy());
        ObjectNode continuity=obj();JsonNode snapshot=source.path("continuitySnapshot");
        for(String field:List.of("worldRules","continuityRules","storyFacts","relationships","timeline","characterStates","locationStates","propStates"))if(snapshot.has(field))continuity.set(field,snapshot.path(field).deepCopy());
        if(!continuity.isEmpty())input.set("continuity",continuity);return input;
    }

    private ObjectNode detailInput(JsonNode source){
        ObjectNode input=obj().put("stage","SHOT_DETAIL").put("sceneTargetDurationSeconds",source.path("sceneTargetDurationSeconds").asDouble());
        for(String field:List.of("scene","project","episodeFormat","episodeScript","directorPlan","dramaticBeats","sceneState","presenceLedger","shotSkeletons","assets","previousShotContinuity","directorRuleProfile"))input.set(field,source.path(field).deepCopy());
        input.set("currentState",visibleState(source.path("currentState"),source.path("assets")));return input;
    }

    private ObjectNode relevantAssets(JsonNode all,JsonNode skeletons,JsonNode previous){
        Set<String> actors=new LinkedHashSet<>(),props=new LinkedHashSet<>();for(JsonNode skeleton:skeletons){skeleton.path("characterIds").forEach(v->actors.add(v.asText()));skeleton.path("propIds").forEach(v->props.add(v.asText()));}
        previous.path("blocking").path("characters").forEach(v->actors.add(text(v,"characterId")));
        ObjectNode result=obj();for(String field:List.of("characters","looks","locations","props")){ArrayNode values=result.putArray(field);for(JsonNode value:all.path(field)){
            boolean include=field.equals("locations")||field.equals("characters")&&actors.contains(id(value))||field.equals("looks")&&actors.contains(text(value,"characterId"))||field.equals("props")&&props.contains(id(value));if(include)values.add(value.deepCopy());}}
        return result;
    }

    private ObjectNode visibleState(JsonNode state,JsonNode assets){
        ObjectNode result=obj(),characters=result.putObject("characters"),props=result.putObject("props");
        for(JsonNode actor:assets.path("characters"))if(state.path("characters").has(id(actor)))characters.set(id(actor),state.path("characters").path(id(actor)).deepCopy());
        for(JsonNode prop:assets.path("props"))if(state.path("props").has(id(prop)))props.set(id(prop),state.path("props").path(id(prop)).deepCopy());return result;
    }

    private void finalizePlan(String rootId){
        ObjectNode root=store.getForUpdate(GENERATION_JOB,rootId);requireCurrent(root);JsonNode plan=root.path("outputSnapshot");int expected=plan.path("batchCount").asInt();
        Map<Integer,ObjectNode> successful=new TreeMap<>();for(ObjectNode job:store.list(GENERATION_JOB,project(root),null))if("SHOT_DETAIL".equals(text(job,"type"))&&rootId.equals(text(job.path("inputSnapshot"),"rootPlanJobId"))&&"SUCCESS".equals(text(job,"status")))successful.put(job.path("inputSnapshot").path("batchIndex").asInt(),job);
        if(successful.size()<expected)return;
        ObjectNode scene=store.getForUpdate(SCENE,required(root.path("inputSnapshot").path("scene"),"id"));
        for(ObjectNode existing:store.list(SHOT,project(root),id(scene)))if(rootId.equals(text(existing,"directorPlanJobId"))&&!existing.path("stale").asBoolean())return;
        ObjectNode providerInput=planInput(root.path("inputSnapshot")),full=obj();providerInput.set("directorRuleProfile",plan.path("directorRuleProfile").deepCopy());full.set("directorPlan",plan.path("directorPlan").deepCopy());full.set("dramaticBeats",plan.path("dramaticBeats").deepCopy());full.set("sceneState",plan.path("sceneState").deepCopy());full.set("presenceLedger",plan.path("presenceLedger").deepCopy());ArrayNode raw=full.putArray("shots");
        Map<Integer,String> detailJobByShot=new HashMap<>();for(ObjectNode batch:successful.values())for(JsonNode shot:batch.path("outputSnapshot").path("shots")){raw.add(shot.deepCopy());detailJobByShot.put(raw.size(),id(batch));}
        contract.validate(full,providerInput,text(root,"providerRequestId"));ArrayNode materialized=contract.materialize(full,providerInput);
        Map<String,JsonNode> beats=new HashMap<>();for(JsonNode beat:full.path("dramaticBeats"))beats.put(text(beat,"beatId"),beat);
        ArrayNode ids=JsonNodeFactory.instance.arrayNode();int number=0;List<String> orderedBeats=new ArrayList<>();Map<String,Integer> firstByBeat=new LinkedHashMap<>(),lastByBeat=new LinkedHashMap<>();for(int index=0;index<materialized.size();index++){String beat=text(materialized.path(index),"beatId");if(!beat.isBlank()){if(!orderedBeats.contains(beat))orderedBeats.add(beat);firstByBeat.putIfAbsent(beat,index);lastByBeat.put(beat,index);}}
        for(int shotIndex=0;shotIndex<materialized.size();shotIndex++){JsonNode shot=materialized.path(shotIndex);ObjectNode next=(ObjectNode)shot.deepCopy();String beatId=text(shot,"beatId");ArrayNode completed=next.putArray("completedBeats"),reserved=next.putArray("reservedFutureBeats");for(String beat:orderedBeats){if(lastByBeat.get(beat)<shotIndex)completed.add(beat);else if(firstByBeat.get(beat)>shotIndex)reserved.add(beat);}next.set("currentBeat",beats.getOrDefault(beatId,obj()).deepCopy());next.remove("dialogues");next.putArray("dialogueIds");next.put("projectId",project(root)).put("sceneId",id(scene)).put("shotNo",++number);ObjectNode saved=studio.create(SHOT,next);ArrayNode dialogueIds=JsonNodeFactory.instance.arrayNode();String detailJobId=detailJobByShot.get(number);
            for(JsonNode dialogue:shot.path("dialogues")){String dialect=root.path("inputSnapshot").path("project").path("dialect").asText("MANDARIN");ObjectNode line=(ObjectNode)dialogue.deepCopy();line.put("projectId",project(root)).put("shotId",id(saved)).put("generationJobId",detailJobId).put("storyDocumentId",text(root.path("inputSnapshot").path("episodeScript"),"storyDocumentId")).put("dialect",dialect).put("dialectStrength",dialect.equals("MANDARIN")?0:1);line.put("dialectText",dialect.equals("MANDARIN")?text(dialogue,"displayText"):"").put("speechText",dialect.equals("MANDARIN")?text(dialogue,"displayText"):"").put("needsHumanCorrection",!dialect.equals("MANDARIN"));dialogueIds.add(id(store.create(DIALOGUE_LINE,line)));}
            ObjectNode persisted=saved.deepCopy().put("generationJobId",detailJobId).put("shotDetailJobId",detailJobId).put("directorPlanJobId",rootId).put("continuityHash",text(root.path("inputSnapshot"),"continuityHash")).put("planningSignature",required(root.path("inputSnapshot"),"planningSignature")).put("planVersion",root.path("inputSnapshot").path("planVersion").asInt()).put("directorPlanVersion",root.path("inputSnapshot").path("directorPlanVersion").asInt()).put("dramaticBeatVersion",root.path("inputSnapshot").path("dramaticBeatVersion").asInt()).put("shotPlanVersion",root.path("inputSnapshot").path("shotPlanVersion").asInt()).put("stateDerivation","SCENE_INITIAL_PLUS_DELTA").put("stale",false);
            persisted.set("directorPlanSnapshot",full.path("directorPlan").deepCopy());persisted.set("directorRuleProfile",plan.path("directorRuleProfile").deepCopy());persisted.set("dramaticBeatSnapshot",beats.get(text(shot,"beatId")).deepCopy());persisted.set("shotPlanSnapshot",next.deepCopy());persisted.set("dialogueIds",dialogueIds);persisted.set("assetViewIds",root.path("inputSnapshot").path("assetViewIds").deepCopy());store.update(SHOT,id(saved),revision(saved),persisted);ids.add(id(saved));}
        double finalDuration=0;for(JsonNode shot:materialized)finalDuration+=shot.path("duration").asDouble();
        ObjectNode completed=(ObjectNode)plan.deepCopy();completed.put("detailsComplete",true).put("finalShotCount",ids.size()).put("finalDuration",finalDuration);completed.set("shotIds",ids);completed.set("validation",obj().put("passed",true).put("message","导演规划、分批镜头详情、状态增量与跨批连续性已校验"));jobs.succeed(rootId,completed);
        store.update(SCENE,id(scene),revision(scene),scene.deepCopy().put("directorPlanStatus","SUCCESS").put("shotCount",ids.size()).set("sceneContinuityPolicy",sceneContinuityPolicy(scene,root.path("inputSnapshot").path("project"))));
    }

    private ObjectNode sceneContinuityPolicy(JsonNode scene,JsonNode project){
        JsonNode configured=scene.path("sceneContinuityPolicy").isObject()?scene.path("sceneContinuityPolicy"):project.path("sceneContinuityPolicy");
        ObjectNode policy=configured.isObject()?(ObjectNode)configured.deepCopy():obj();
        int max=policy.path("maxContinuationDepth").asInt(2);if(max<1||max>8)throw new WorkflowException("SCENE_CONTINUITY_POLICY_INVALID","maxContinuationDepth 必须在 1 到 8 之间");
        policy.put("maxContinuationDepth",max);policy.putIfAbsent("resetAtSceneBoundary",BooleanNode.TRUE);policy.putIfAbsent("reanchorFromCanonical",BooleanNode.TRUE);policy.putIfAbsent("reanchorOnIdentityDrift",BooleanNode.TRUE);policy.putIfAbsent("reanchorOnLocationDrift",BooleanNode.TRUE);policy.putIfAbsent("driftWarningCount",IntNode.valueOf(0));return policy;
    }

    private void requireCurrent(ObjectNode job){
        String rootId="SHOT_DETAIL".equals(text(job,"type"))?required(job.path("inputSnapshot"),"rootPlanJobId"):id(job);ObjectNode root=rootId.equals(id(job))?job:store.get(GENERATION_JOB,rootId);ObjectNode input=(ObjectNode)root.path("inputSnapshot");ObjectNode scene=store.get(SCENE,required(input.path("scene"),"id"));
        String active=text(scene,"activeDirectorPlanJobId"),revalidationOf=text(input,"revalidationOfJobId");boolean current=rootId.equals(active)||(!revalidationOf.isBlank()&&revalidationOf.equals(active));
        if(scene.path("stale").asBoolean()||!current||!required(input,"planningSignature").equals(text(scene,"activeDirectorPlanSignature")))throw new WorkflowException("STALE_DIRECTOR_PLAN","此导演规划已被新的剧本或素材版本替代，结果不加入当前镜头");
        for(JsonNode reference:input.path("assetViewIds")){ObjectNode view=store.get(ASSET_VIEW,reference.asText());if(view.path("stale").asBoolean()||!view.path("approved").asBoolean())throw new WorkflowException("ASSET_VERSION_CHANGED","导演规划期间素材参考已修改或退回，请使用当前素材版本重新规划");}
    }

    private void activateRevalidatedPlan(ObjectNode job){
        if(!"DIRECTOR_PLAN".equals(text(job,"type")))return;ObjectNode input=(ObjectNode)job.path("inputSnapshot");String original=text(input,"revalidationOfJobId");if(original.isBlank())return;
        ObjectNode scene=store.getForUpdate(SCENE,required(input.path("scene"),"id"));String active=text(scene,"activeDirectorPlanJobId");if(id(job).equals(active))return;if(!original.equals(active))throw new WorkflowException("STALE_DIRECTOR_PLAN","原导演规划已不再是当前版本，本地复核不能接管场景");
        store.update(SCENE,id(scene),revision(scene),scene.deepCopy().put("activeDirectorPlanJobId",id(job)));
    }

    private ObjectNode demoPlan(JsonNode input){
        double target=input.path("sceneTargetDurationSeconds").asDouble(6),average=input.path("directorStyleProfile").path("averageShotLength").asDouble(3);if(average<2||average>5)average=3;
        int count=Math.max(1,(int)Math.round(target/average));count=Math.max((int)Math.ceil(target/5),Math.min((int)Math.floor(target/2),count));double base=target/count,allocated=0;double[] durations=new double[count];for(int i=0;i<count-1;i++){double offset=i%3==0?-.25:i%3==1?.25:0;durations[i]=Math.max(2,Math.min(5,Math.round((base+offset)*100d)/100d));allocated+=durations[i];}durations[count-1]=Math.round((target-allocated)*100d)/100d;
        JsonNode assets=input.path("assets");String locationId=text(assets.path("locations").path(0),"id");if(locationId.isBlank())throw new WorkflowException("LOCATION_REQUIRED","拆镜前需要已确认的场景资产");
        ObjectNode out=obj();out.set("directorPlan",obj().put("planPurpose","从环境建立推进到人物反应和信息揭示").put("scenePacing","TENSION_BUILD").put("shotRepetitionReason","").put("cameraStrategy","固定主轴，以景别变化和必要反应镜头组织信息").set("styleProfile",input.path("directorStyleProfile").deepCopy()));
        ArrayNode acceptedBeats=(ArrayNode)input.path("scriptBeats");int beatCount=Math.max(1,acceptedBeats.size());String[] defaults={"建立人物、空间与当前目标","阻力出现并改变人物判断","人物作出选择并形成场景结果"};
        String[] beforeEmotions={"平静","怀疑","紧张"},afterEmotions={"怀疑","紧张","恐惧"};ArrayNode beatNodes=out.putArray("dramaticBeats");List<String> beatIds=new ArrayList<>();for(int b=0;b<beatCount;b++){JsonNode source=acceptedBeats.path(b);String beatId=text(source,"beatId");if(beatId.isBlank())beatId="BT"+String.format("%02d",b+1);beatIds.add(beatId);String purpose=text(source,"purpose");if(purpose.isBlank())purpose=defaults[Math.min(b,defaults.length-1)];boolean reveal=b>0;ObjectNode beat=obj().put("beatId",beatId).put("beatPurpose",purpose).put("action",purpose).put("conflict",b==0?"人物尚未掌握全部环境信息":"新信息迫使人物调整行动").put("emotionBefore",beforeEmotions[Math.min(b,beforeEmotions.length-1)]).put("emotionAfter",afterEmotions[Math.min(b,afterEmotions.length-1)]).put("informationReveal",reveal?"按已确认节拍改变人物判断":"本节拍只推进已确认行动").put("importance",source.path("required").asBoolean(true)?"HIGH":"MEDIUM").put("suggestedDuration",Math.round(target/beatCount*100d)/100d);ArrayNode active=beat.putArray("activeCharacters");assets.path("characters").forEach(a->active.add(id(a)));beat.putArray("storyFactChanges");beat.putArray("relationshipChanges");beatNodes.add(beat);}
        out.set("sceneState",obj().put("locationId",locationId).put("time","本场连续时间").put("lighting","主光源固定在场景左后方").put("spatialRelations","固定入口、主要活动区和关键设施保持同一世界方位"));ObjectNode state=out.putObject("sceneInitialState"),characters=state.putObject("characters"),props=state.putObject("props");ArrayNode presence=out.putArray("presenceLedger");int actorNo=0;for(JsonNode actor:assets.path("characters")){String actorId=id(actor),position="主表演区站位"+(++actorNo);characters.set(actorId,obj().put("identityId",actorId).put("lookId",text(actor,"baseLookId")).put("position",position).put("lookDirection","朝向主要动作区").put("holding","").put("pose","自然站立").put("actionState","准备开始本场动作"));presence.add(obj().put("characterId",actorId).put("presence",actorNo==1?"PRESENT":"OFFSCREEN").put("visibility",actorNo==1?"FOREGROUND":"OFFSCREEN").put("anchor",position));}for(JsonNode prop:assets.path("props")){String propId=id(prop);props.set(propId,obj().put("holder","").put("position","场景内固定道具位置").put("state",prop.path("state").asText("完整")));}
        String authoredDialogue=authoredDialogueForDuration(text(input.path("episodeScript"),"script"),durations[0]);ArrayNode skeletons=out.putArray("shotSkeletons");for(int i=0;i<count;i++){int beatIndex=Math.min(beatCount-1,(int)((long)i*beatCount/count));String purpose=text(acceptedBeats.path(beatIndex),"purpose");if(purpose.isBlank())purpose=defaults[Math.min(beatIndex,defaults.length-1)];ObjectNode shot=obj().put("shotIndex",i+1).put("beatId",beatIds.get(beatIndex)).put("purpose",i==0?"建立空间与人物方位":purpose).put("feltIntent",i==0?"让观众立刻看清人物与危险空间的相对位置":"让观众从人物的动作和反应中感到局势正在收紧").put("action","人物完成一个清晰动作并观察结果").put("dramaticJob",i==0?"INFORMATION_CHANGE":"ACTION_ADVANCE").put("subject",assets.path("characters").isEmpty()?locationId:id(assets.path("characters").path(0))).put("dialogueOwner","").put("duration",durations[i]).put("shotSize",i%3==0?"WIDE":i%3==1?"MEDIUM":"CLOSE_UP").put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("relationToPrevious",i==0?"ESTABLISHING":i%3==2?"REACTION":"CONTINUOUS").put("directorIntent",i==0?"ESTABLISH_SPACE":i%3==2?"SHOW_REACTION":"SHOW_ACTION").put("transition","CUT");ArrayNode covers=shot.putArray("coversBeats");int coverStart=(int)((long)i*beatCount/count),coverEnd=Math.max(coverStart,(int)((long)(i+1)*beatCount/count)-1);for(int b=coverStart;b<=Math.min(beatCount-1,coverEnd);b++)covers.add(beatIds.get(b));shot.set("actionContract",obj().put("start","人物处于已确认起始姿态").put("action","完成一个原子动作").put("contact","无接触时明确保持空间关系").put("consequence","动作产生可见结果").put("endpoint","停在下一镜可承接的明确姿态"));shot.putArray("secondarySubjects");ArrayNode charIds=shot.putArray("characterIds");if(!assets.path("characters").isEmpty())charIds.add(id(assets.path("characters").path(0)));ArrayNode offscreen=shot.putArray("offscreenCharacterIds");for(int actorIndex=1;actorIndex<assets.path("characters").size();actorIndex++)offscreen.add(id(assets.path("characters").path(actorIndex)));shot.putArray("exitedCharacterIds");if(i==0&&!charIds.isEmpty()&&!authoredDialogue.isBlank())shot.put("dialogueOwner",charIds.path(0).asText());shot.putArray("propIds");ObjectNode blocking=shot.putObject("basicBlocking").put("cameraWorldPosition","主要活动区南侧两米").put("axis","入口—主要人物主表演轴").put("axisSide","A_SIDE").put("axisChangeReason","").put("keyObjectPositions","入口在北侧，关键设施保持固定").put("doorWindowState","门窗状态沿用场景初始设定");ArrayNode blockingActors=blocking.putArray("characters"),anchors=blocking.putArray("spatialAnchors");for(JsonNode actor:charIds){JsonNode actorState=characters.path(actor.asText());blockingActors.add(obj().put("characterId",actor.asText()).put("worldPosition",text(actorState,"position")).put("facing","朝向主要动作区").put("screenDirection","FRAME_RIGHT"));anchors.add(obj().put("subject",actor.asText()).put("anchorObject","SCENE_AXIS:入口—主要人物主表演轴").put("relation","AT_WORLD_POSITION").put("facing","朝向主要动作区").put("distance",text(actorState,"position").isBlank()?"主要活动区":text(actorState,"position")).put("side","A_SIDE"));}skeletons.add(shot);}return out;
    }

    private ObjectNode demoDetail(JsonNode input){
        ObjectNode out=obj();double firstDuration=input.path("shotSkeletons").path(0).path("duration").asDouble();String authoredDialogue=authoredDialogueForDuration(text(input.path("episodeScript"),"script"),firstDuration);ArrayNode details=out.putArray("shots");for(JsonNode skeleton:input.path("shotSkeletons")){ObjectNode detail=obj().put("shotIndex",skeleton.path("shotIndex").asInt()).put("visualFocus",text(skeleton,"subject")).put("emotion","警觉并持续判断").put("expression","视线稳定，眉间轻微收紧").put("eyeLine","沿主表演轴看向动作区").put("focus",text(skeleton,"subject")).put("difficulty","B");ObjectNode blocking=detail.putObject("blocking");ArrayNode actors=blocking.putArray("characters");for(JsonNode actor:skeleton.path("characterIds"))actors.add(obj().put("characterId",actor.asText()).put("framePosition","画面三分线位置").put("eyeLineTarget","主要动作区").put("visibleBodyPart","WHOLE_BODY").put("bodyFrameSide","IN_FRAME_CENTER").put("limbEntrySide","NONE").put("contactPoint",""));String performanceLevel=text(input.path("directorRuleProfile"),"performanceDetail");if(performanceLevel.isBlank())performanceLevel="BASIC";ObjectNode performance=obj().put("primaryAction","完成一个原子动作").put("microExpression",performanceLevel.equals("BASIC")?"保持自然表情，不额外强调肌肉变化":"眉间逐渐收紧").put("bodyLanguage","重心稳定并轻微前倾").put("gaze","锁定主要动作区").put("gesture","双手保持与动作一致").put("actionUnits",1).put("detailLevel",performanceLevel).put("microExpressionStage",performanceLevel.equals("BASIC")?"NONE":"INITIAL_REACTION");performance.set("propOperations",arr());performance.set("performanceCues",obj().put("eyes",performanceLevel.equals("BASIC")?"":"视线先停顿再锁定动作区").put("jaw",performanceLevel.equals("BASIC")?"":"下颌短暂收紧").put("breath",performanceLevel.equals("BASIC")?"":"吸气停半拍").put("hands","手势保持动作连续").put("shoulders","肩部随呼吸轻微起伏").put("pause",performanceLevel.equals("BASIC")?"":"反应前停顿0.2秒"));ArrayNode facs=performance.putArray("facsUnits");if(performanceLevel.equals("FACS")){facs.add("AU4").add("AU7");}detail.set("performancePlan",performance);String size=text(skeleton,"shotSize"),required=Set.of("EXTREME_WIDE","WIDE","FULL","MEDIUM_FULL").contains(size)?"ACTION":"IDENTITY";detail.set("visibilityPlan",obj().put("occlusion","NONE").put("requiredDetail",required).set("visibleFeatures",arr().add("主体轮廓与动作")));detail.set("cameraPlan",obj().put("position",text(skeleton.path("basicBlocking"),"cameraWorldPosition")).put("height","1.5米").put("distance","距离主体2米").put("lensPreset","LENS_50MM").put("horizontalAngle","沿主轴向北0度").put("verticalAngle","水平0度").put("subjectPlacement","主体位于画面三分线").put("focusPoint",text(skeleton,"subject")).put("depthOfField","中等景深，主体和关键环境可辨").put("lightingDirection","固定主光从画面右后方入射").put("movementPath","固定").put("movementSpeed","0米每秒"));ArrayNode dialogues=detail.putArray("dialogues");if(skeleton.path("shotIndex").asInt()==1&&!skeleton.path("characterIds").isEmpty()&&!authoredDialogue.isBlank()){long end=Math.round(skeleton.path("duration").asDouble()*1000);dialogues.add(obj().put("characterId",skeleton.path("characterIds").path(0).asText()).put("displayText",authoredDialogue).put("emotion","克制而警觉").put("startMs",0).put("endMs",end));}ObjectNode refs=detail.putObject("referenceViews");ArrayNode characterViews=refs.putArray("characterViews");for(JsonNode ignored:skeleton.path("characterIds"))characterViews.add("FRONT");refs.put("locationView","FRONT");ArrayNode propViews=refs.putArray("propViews");for(JsonNode ignored:skeleton.path("propIds"))propViews.add("FRONT");detail.putArray("stateChanges");details.add(detail);}return out;
    }

    private String firstAuthoredDialogue(String script){if(script==null||script.isBlank())return "";java.util.regex.Matcher quoted=java.util.regex.Pattern.compile("[“\\\"]([^”\\\"]{1,80})[”\\\"]").matcher(script);if(quoted.find())return quoted.group(1).trim();java.util.regex.Matcher labelled=java.util.regex.Pattern.compile("(?:^|[。！？\\n])[^。！？\\n：:]{1,16}[：:]\\s*([^。！？\\n]{1,80}[。！？]?)").matcher(script);return labelled.find()?labelled.group(1).trim():"";}

    private String authoredDialogueForDuration(String script,double duration){String line=firstAuthoredDialogue(script);int maxChars=(int)Math.floor((Math.max(0,duration)-.35)*4.5);if(line.isBlank()||maxChars<1)return "";String best="";for(String raw:line.split("[，,；;]")){String candidate=raw.trim().replaceFirst("[。！？!?]+$","");int chars=candidate.codePointCount(0,candidate.length());if(chars<=maxChars&&chars>best.codePointCount(0,best.length()))best=candidate;}return best;}

    private ObjectNode compact(JsonNode source,List<String> fields){ObjectNode result=obj();for(String field:fields)if(source.has(field))result.set(field,source.path(field).deepCopy());return result;}
    private record DirectorRuleSelection(String content,String fingerprint,ObjectNode profile,List<RuntimeRulePackLoader.RuleFragment> rules){}
    private DirectorRuleSelection directorRules(JsonNode input){JsonNode configured=input.path("directorRuleProfile");String spatial=text(configured,"spatialComplexity"),asset=text(configured,"assetDependency"),performance=text(configured,"performanceDetail");if(spatial.isBlank()){int actors=input.path("assets").path("characters").size();String script=text(input.path("episodeScript"),"script");boolean complex=script.matches(".*(?:追逐|打斗|战斗|围攻|穿过|冲出).*"),combat=script.matches(".*(?:打斗|战斗|围攻).*" );spatial=new SpatialComplexityAnalyzer().classify(actors,complex?3:0,complex,combat).name();}if(asset.isBlank()){int actors=input.path("assets").path("characters").size(),locations=input.path("assets").path("locations").size();asset=new AssetDependencyAnalyzer().classify(actors,locations,input.path("shotSkeletons").size(),actors>0,false).name();}if(performance.isBlank()){performance=input.path("directorStyleProfile").path("performanceDetail").asText("BASIC");if(!Set.of("BASIC","MICRO_EXPRESSION","FACS").contains(performance))performance="BASIC";}ObjectNode profile=obj().put("spatialComplexity",spatial).put("assetDependency",asset).put("performanceDetail",performance);var assembled=rulePackAssembler.assemble(rulePackResolver.director(spatial,asset,performance));var budgeted=rulePackBudgeter.fit(assembled,48000);return new DirectorRuleSelection(budgeted.content(),assembled.fingerprint(),profile,budgeted.included());}
    private void auditRules(ObjectNode job,DirectorRuleSelection selection){jobs.mutate(id(job),j->{j.put("rulePackFingerprint",selection.fingerprint()).put("rulePackContentChars",selection.content().length());j.set("directorRuleProfile",selection.profile().deepCopy());ArrayNode loaded=j.putArray("loadedRules");for(var rule:selection.rules())loaded.add(obj().put("ruleId",rule.ruleId()).put("namespace",rule.namespace().name()).put("contentHash",rule.contentHash()).put("sourceRepo",rule.sourceRepo()).put("upstreamCommit",rule.upstreamCommit()).put("sourcePath",rule.sourcePath()));});}
    private ArrayNode arr(){return JsonNodeFactory.instance.arrayNode();}
    private ObjectNode usage(LlmGateway.ProviderUsage usage){return obj().put("promptTokens",usage.promptTokens()).put("completionTokens",usage.completionTokens()).put("totalTokens",usage.totalTokens());}
    private String prompt(){try(var stream=new ClassPathResource("development-skills/06-shot-planning/prompt.md").getInputStream()){return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("导演技能加载失败",e);}}
}
