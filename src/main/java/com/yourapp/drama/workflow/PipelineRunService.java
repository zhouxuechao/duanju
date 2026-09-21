package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class PipelineRunService {
    private static final List<String> STAGES=List.of("PREFLIGHT","CORE","OUTLINE","SCRIPT","SCENE","SHOT_PLAN","KEYFRAME","VIDEO","TTS","TIMELINE","PREVIEW","FINAL_QC");
    private final DocumentStore store;
    private final PipelinePreflightService preflight;
    private final ObjectMapper mapper;
    public PipelineRunService(DocumentStore store,PipelinePreflightService preflight,ObjectMapper mapper){this.store=store;this.preflight=preflight;this.mapper=mapper;}

    public ObjectNode start(String projectId,ObjectNode request){
        store.get(PROJECT,projectId);
        String mode=text(request,"mode").isBlank()?"MOCK":text(request,"mode").toUpperCase(Locale.ROOT);
        String scenario=text(request,"scenarioId").isBlank()?"golden-basic":text(request,"scenarioId");
        ObjectNode run=store.create(PIPELINE_RUN,obj().put("projectId",projectId).put("scenarioId",scenario).put("mode",mode)
            .put("status","RUNNING").put("attempt",1).put("startedAt",Instant.now().toString()).put("plannedCost",0).put("actualCost",0).put("wasteCost",0));
        return audit(run,false);
    }

    public ObjectNode resume(String runId){
        ObjectNode run=store.transaction(()->{ObjectNode current=store.getForUpdate(PIPELINE_RUN,runId);if("SUCCESS".equals(text(current,"status")))return current;return store.update(PIPELINE_RUN,runId,revision(current),current.deepCopy().put("status","RUNNING").put("attempt",current.path("attempt").asInt(1)+1).put("resumedAt",Instant.now().toString()));});
        return audit(run,true);
    }

    public ObjectNode get(String runId){return response(store.get(PIPELINE_RUN,runId));}

    private ObjectNode audit(ObjectNode run,boolean resume){
        String projectId=project(run),runId=id(run),mode=text(run,"mode");
        ObjectNode preflightResult=preflight.review(projectId,mode);
        Map<String,StageEvaluation> evaluations=evaluate(projectId,preflightResult);
        boolean blocked=false;
        for(String stage:STAGES){
            StageEvaluation evaluation=evaluations.get(stage);
            String status=blocked?"PENDING":evaluation.success?"SUCCESS":"PENDING";
            if(!evaluation.success)blocked=true;
            Optional<ObjectNode> previous=latestStage(projectId,runId,stage);
            if(resume&&"SUCCESS".equals(status)&&previous.filter(p->"SUCCESS".equals(text(p,"status"))&&evaluation.inputFingerprint.equals(text(p,"inputFingerprint"))).isPresent())continue;
            int attempt=previous.map(p->p.path("attempt").asInt()+1).orElse(1);
            ObjectNode checkpoint=obj().put("projectId",projectId).put("pipelineRunId",runId).put("stage",stage).put("status",status).put("attempt",attempt)
                .put("startedAt",Instant.now().toString()).put("finishedAt",Instant.now().toString()).put("durationMs",0)
                .put("inputFingerprint",evaluation.inputFingerprint).put("outputFingerprint",evaluation.outputFingerprint)
                .put("safeRetry",!"PROVIDER_UNCERTAIN".equals(evaluation.rootCauseCategory)).put("cost",0);
            checkpoint.putArray("artifacts").addAll(evaluation.artifacts);
            if(!"SUCCESS".equals(status)){checkpoint.put("errorCode",evaluation.errorCode).put("errorMessage",evaluation.message).put("rootCauseCategory",evaluation.rootCauseCategory);}
            store.create(STAGE_RUN,checkpoint);
        }
        String resumeFrom=STAGES.stream().filter(stage->latestStage(projectId,runId,stage).map(p->!"SUCCESS".equals(text(p,"status"))).orElse(true)).findFirst().orElse("");
        ObjectNode updated=store.transaction(()->{ObjectNode current=store.getForUpdate(PIPELINE_RUN,runId),next=current.deepCopy();boolean success=resumeFrom.isBlank();next.put("status",success?"SUCCESS":"WAITING").put("resumeFromStage",resumeFrom).put("lastCheckedAt",Instant.now().toString());if(success)next.put("finishedAt",Instant.now().toString());return store.update(PIPELINE_RUN,runId,revision(current),next);});
        return response(updated);
    }

    private Map<String,StageEvaluation> evaluate(String projectId,ObjectNode pf){
        List<ObjectNode> docs=store.list(STORY_DOCUMENT,projectId,null),episodes=store.list(EPISODE,projectId,null),scenes=store.list(SCENE,projectId,null),shots=store.list(SHOT,projectId,null);
        Map<String,StageEvaluation> values=new LinkedHashMap<>();
        values.put("PREFLIGHT",evaluation(pf.path("ready").asBoolean(),"PREFLIGHT_BLOCKED","配置或工具自检存在阻塞项","CONFIG",pf,List.of()));
        values.put("CORE",resources(docs.stream().filter(d->"CORE".equals(text(d,"documentType"))&&!d.path("stale").asBoolean()).toList(),d->"CONFIRMED".equals(text(d,"reviewStatus")),"CORE_NOT_CONFIRMED","整季核心尚未确认"));
        values.put("OUTLINE",resources(docs.stream().filter(d->"OUTLINE_BATCH".equals(text(d,"documentType"))&&!d.path("stale").asBoolean()).toList(),d->"CONFIRMED".equals(text(d,"reviewStatus")),"OUTLINE_NOT_CONFIRMED","分批集纲尚未全部确认"));
        values.put("SCRIPT",resources(docs.stream().filter(d->"EPISODE_SCRIPT".equals(text(d,"documentType"))&&!d.path("stale").asBoolean()).toList(),d->"CONFIRMED".equals(text(d,"reviewStatus")),"SCRIPT_NOT_CONFIRMED","单集剧本尚未全部确认"));
        values.put("SCENE",resources(scenes,d->true,"SCENE_MISSING","尚未形成场景"));
        values.put("SHOT_PLAN",resources(shots,d->!d.path("stale").asBoolean(),"SHOT_PLAN_MISSING","尚未形成有效镜头计划"));
        values.put("KEYFRAME",resourcesForShots(projectId,shots,KEYFRAME,"KEYFRAME_NOT_LOCKED","镜头缺少已质检并锁定的关键帧"));
        values.put("VIDEO",resourcesForShots(projectId,shots,VIDEO_TAKE,"VIDEO_NOT_LOCKED","镜头缺少已质检并锁定的视频"));
        List<ObjectNode> dialogue=store.list(DIALOGUE_LINE,projectId,null),audio=store.list(AUDIO_CLIP,projectId,null);
        boolean tts=!dialogue.isEmpty()&&dialogue.stream().allMatch(line->audio.stream().anyMatch(a->id(line).equals(text(a,"dialogueLineId"))&&a.path("locked").asBoolean()));
        values.put("TTS",evaluation(tts,"TTS_INCOMPLETE","对白尚未全部生成并锁定配音","DATA",mapper.valueToTree(dialogue),artifacts(audio)));
        List<ObjectNode> timelines=store.list(TIMELINE,projectId,null);
        values.put("TIMELINE",resources(timelines,t->true,"TIMELINE_MISSING","尚未生成时间线"));
        values.put("PREVIEW",resources(timelines,t->!text(t,"previewUrl").isBlank()&&!t.path("previewStale").asBoolean(),"PREVIEW_MISSING","当前剪辑版本尚未生成有效预览"));
        values.put("FINAL_QC",resources(timelines,t->!text(t,"finalUrl").isBlank()&&t.path("finalTechnicalQa").path("passed").asBoolean()&&"PASS".equals(text(t,"finalQaStatus")),"FINAL_QC_PENDING","终片技术与人工验收尚未全部通过"));
        return values;
    }

    private StageEvaluation resources(List<ObjectNode> resources,java.util.function.Predicate<ObjectNode> accepted,String code,String message){boolean success=!resources.isEmpty()&&resources.stream().allMatch(accepted);return evaluation(success,code,message,"DATA",mapper.valueToTree(resources),artifacts(resources));}
    private StageEvaluation resourcesForShots(String projectId,List<ObjectNode> shots,ResourceKind kind,String code,String message){List<ObjectNode> candidates=store.list(kind,projectId,null);boolean success=!shots.isEmpty()&&shots.stream().allMatch(shot->candidates.stream().anyMatch(c->id(shot).equals(text(c,"shotId"))&&c.path("selected").asBoolean()&&c.path("locked").asBoolean()&&"PASSED".equals(text(c,"qcStatus"))));return evaluation(success,code,message,"DATA",mapper.valueToTree(candidates),artifacts(candidates));}
    private StageEvaluation evaluation(boolean success,String code,String message,String category,JsonNode source,List<String> artifacts){return new StageEvaluation(success,code,message,category,hash(source),success?hash(mapper.valueToTree(artifacts)):"",mapper.valueToTree(artifacts));}
    private List<String> artifacts(List<ObjectNode> docs){return docs.stream().map(Documents::id).toList();}
    private Optional<ObjectNode> latestStage(String projectId,String runId,String stage){return store.list(STAGE_RUN,projectId,runId).stream().filter(s->stage.equals(text(s,"stage"))).max(Comparator.comparingInt(s->s.path("attempt").asInt()));}
    private ObjectNode response(ObjectNode run){ObjectNode out=run.deepCopy();ArrayNode stages=out.putArray("stages");for(String stage:STAGES)latestStage(project(run),id(run),stage).ifPresent(stages::add);return out;}
    private String hash(JsonNode node){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(node)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record StageEvaluation(boolean success,String errorCode,String message,String rootCauseCategory,String inputFingerprint,String outputFingerprint,ArrayNode artifacts){}
}
