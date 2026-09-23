package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Thin canary adapter: all production work is delegated to the normal persisted workflow. */
@Service
public class DefaultPipelineCanaryProductionAdapter implements PipelineCanaryProductionAdapter {
    private final ObjectMapper mapper;
    private final StudioService studio;
    private final PipelineRunService pipelineRuns;
    private final DocumentStore store;
    private final Environment environment;

    public DefaultPipelineCanaryProductionAdapter(ObjectMapper mapper, StudioService studio, PipelineRunService pipelineRuns, DocumentStore store,Environment environment) {
        this.mapper=mapper;this.studio=studio;this.pipelineRuns=pipelineRuns;this.store=store;this.environment=environment;
    }

    @Override public Set<Capability> capabilities(){EnumSet<Capability> result=EnumSet.noneOf(Capability.class);bindings().fieldNames().forEachRemaining(name->{try{result.add(Capability.valueOf(name));}catch(IllegalArgumentException ignored){}});return result;}
    @Override public ObjectNode bindings(){return serviceMap();}

    @Override public ObjectNode start(PipelineCanaryFixture fixture,String runId,String mode){
        if(!Set.of("MOCK","CANARY").contains(mode))throw new IllegalArgumentException("Pipeline Canary mode must be MOCK or CANARY");
        PipelineLiveExecutionProfile profile=PipelineLiveExecutionProfile.phaseB();
        String providerVoiceId=profile.requireVoiceId(environment.getProperty("CANARY_TTS_VOICE_ID"));
        List<ObjectNode> existingProjects=store.list(PROJECT,null,null).stream().filter(value->runId.equals(text(value,"pipelineCanaryRunId"))).toList();
        if(existingProjects.size()>1)throw new WorkflowException("RECONCILIATION_REQUIRED","More than one production project exists for Pipeline Canary run "+runId);
        if(existingProjects.size()==1){
            ObjectNode existingProject=existingProjects.getFirst();ensureProfile(existingProject,providerVoiceId);String projectId=id(existingProject);
            List<ObjectNode> runs=store.list(PIPELINE_RUN,projectId,null).stream().filter(value->"phase-b-production".equals(text(value,"scenarioId"))).toList();
            if(runs.size()>1)throw new WorkflowException("RECONCILIATION_REQUIRED","More than one production pipeline run exists for Pipeline Canary run "+runId);
            if(runs.size()==1){ObjectNode current=runs.getFirst();return "SUCCESS".equals(text(current,"status"))?inspect(projectId,id(current)):resume(id(current));}
            ObjectNode run=pipelineRuns.start(projectId,obj().put("mode",mode).put("scenarioId","phase-b-production"));
            return inspect(projectId,id(run));
        }
        ObjectNode input=fixture.productionProject(mapper).put("pipelineCanaryRunId",runId).put("testRun",true).put("testRunId",runId).put("testPhase","PIPELINE")
                .put("scenarioId","phase-b-production").put("assetDependencyLevel",profile.assetDependency()).put("canaryTtsVoiceId",providerVoiceId);
        ObjectNode project=studio.create(PROJECT,input);
        ObjectNode run=pipelineRuns.start(id(project),obj().put("mode",mode).put("scenarioId","phase-b-production"));
        return inspect(id(project),id(run));
    }

    @Override public ObjectNode resume(String pipelineRunId){
        ObjectNode run=pipelineRuns.resume(pipelineRunId);
        return inspect(project(run),pipelineRunId);
    }

    @Override public ObjectNode reconcile(ObjectNode cursor){
        String projectId=required(cursor,"projectId"),pipelineRunId=required(cursor,"pipelineRunId");ObjectNode current=inspect(projectId,pipelineRunId);
        for(JsonNode expected:cursor.path("providerJobs")){String jobId=text(expected,"generationJobId"),expectedTask=text(expected,"providerTaskId");if(jobId.isBlank()||expectedTask.isBlank())continue;JsonNode actual=JsonNodeFactory.instance.missingNode();for(JsonNode candidate:current.path("providerJobs"))if(jobId.equals(text(candidate,"generationJobId"))){actual=candidate;break;}if(actual.isMissingNode()||!expectedTask.equals(text(actual,"providerTaskId")))throw new WorkflowException("RECONCILIATION_REQUIRED","Canary cursor and production DB disagree for generation job "+jobId);}
        return current;
    }

    @Override public ObjectNode inspect(String projectId,String pipelineRunId){
        ObjectNode run=pipelineRuns.get(pipelineRunId),result=obj().put("projectId",projectId).put("pipelineRunId",pipelineRunId)
                .put("status",text(run,"status")).put("resumeFromStage",text(run,"resumeFromStage"))
                .put("lastErrorCode",text(run,"lastErrorCode")).put("lastErrorMessage",text(run,"lastErrorMessage"));
        List<ObjectNode> episodes=ordered(EPISODE,projectId,null,"episodeNo"),scenes=ordered(SCENE,projectId,null,"sceneNo"),shots=orderedShots(projectId);
        ObjectNode projectDocument=store.get(PROJECT,projectId);result.put("assetDependency",text(projectDocument,"assetDependencyLevel"));result.set("executionProfile",PipelineLiveExecutionProfile.phaseB().toJson());
        if(!episodes.isEmpty())result.put("episodeId",id(episodes.getFirst()));if(!scenes.isEmpty())result.put("sceneId",id(scenes.getFirst()));
        ids(result.putArray("beatIds"),store.list(BEAT,projectId,null));ids(result.putArray("shotIds"),shots);
        ids(result.putArray("keyframeArtifactIds"),selected(projectId,KEYFRAME));ids(result.putArray("videoTakeIds"),store.list(VIDEO_TAKE,projectId,null));
        ids(result.putArray("selectedTakeIds"),selected(projectId,VIDEO_TAKE));ids(result.putArray("audioArtifactIds"),store.list(AUDIO_CLIP,projectId,null));
        List<ObjectNode> timelines=store.list(TIMELINE,projectId,null);ids(result.putArray("timelineIds"),timelines);ids(result.putArray("timelineClipIds"),store.list(TIMELINE_ITEM,projectId,null));
        if(!timelines.isEmpty()){ObjectNode timeline=timelines.getLast();result.put("timelineId",id(timeline)).put("renderArtifactId",text(timeline,"finalRenderJobId"));}
        List<ObjectNode> generationJobs=store.list(GENERATION_JOB,projectId,null);ArrayNode providerJobs=result.putArray("providerJobs");for(ObjectNode job:generationJobs)if(Set.of("KEYFRAME","VIDEO","TTS").contains(text(job,"type"))){ObjectNode item=obj().put("generationJobId",id(job)).put("type",text(job,"type")).put("status",text(job,"status")).put("shotId",text(job,"shotId")).put("providerRequestId",text(job,"providerRequestId")).put("providerTaskId",text(job,"providerTaskId"));providerJobs.add(item);}
        ObjectNode jobCounts=result.putObject("jobCounts");for(String type:List.of("STORY","SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL","ASSET_IMAGE","KEYFRAME","KEYFRAME_QC","VIDEO","VIDEO_QC","TTS","LIPSYNC"))jobCounts.put(type,0);
        ObjectNode llmStages=result.putObject("llmStageCounts");for(String stage:List.of("STORY_BRIEF","PREMISE","CORE","OUTLINE_BATCH","EPISODE_SCRIPT","STORY_QA","DIRECTOR_PLAN","SHOT_DETAIL"))llmStages.put(stage,0);
        for(ObjectNode job:generationJobs){String type=text(job,"type");if(jobCounts.has(type))jobCounts.put(type,jobCounts.path(type).asInt()+1);String stage=switch(type){case "SCRIPT"->"EPISODE_SCRIPT";case "STORY_QA"->"STORY_QA";case "DIRECTOR_PLAN"->"DIRECTOR_PLAN";case "SHOT_DETAIL"->"SHOT_DETAIL";case "STORY"->text(job.path("inputSnapshot"),"phase");default->"";};if(llmStages.has(stage))llmStages.put(stage,llmStages.path(stage).asInt()+1);}
        result.set("productionCounts",obj().put("episodes",episodes.size()).put("scenes",scenes.size()).put("beats",store.list(BEAT,projectId,null).size()).put("shots",shots.size())
                .put("characters",store.list(CHARACTER,projectId,null).size()).put("locations",store.list(LOCATION,projectId,null).size()).put("props",store.list(PROP,projectId,null).size())
                .put("dialogues",store.list(DIALOGUE_LINE,projectId,null).size()).put("keyframes",store.list(KEYFRAME,projectId,null).size()).put("videoTakes",store.list(VIDEO_TAKE,projectId,null).size())
                .put("audioClips",store.list(AUDIO_CLIP,projectId,null).size()).put("timelineClips",store.list(TIMELINE_ITEM,projectId,null).size()));
        result.set("serviceMap",serviceMap());return result;
    }

    private ObjectNode serviceMap(){return obj().put("PROJECT_PERSISTENCE","StudioService.create(PROJECT)").put("EPISODE_SCENE","StoryDevelopmentService.materializeEpisode")
            .put("STORY","PipelineRunService.driveStory").put("BEAT_SHOT","WorkflowService.plan + DirectorGenerationService")
            .put("CONTINUITY","WorkflowService.context + production resolvers").put("PROMPT_IR","ProductionService.compileImage/compileVideo")
            .put("KEYFRAME_WORKFLOW","WorkflowService.image").put("KEYFRAME_QC","AutomaticVisualReviewService")
            .put("VIDEO_TAKE","WorkflowService.video").put("VIDEO_QC","AutomaticVideoReviewService").put("SELECTED_TAKE","WorkflowService.lock")
            .put("DIALOGUE_THREE_TRACK","DialogueLine").put("TTS","PostProductionService.tts").put("TIMELINE_CLIP","PostProductionService.timeline")
            .put("RENDER","PostProductionService.render").put("FINAL_QA","PostProductionService.quality/finalReview").put("DOMAIN_RECONCILIATION","PipelineRunService.resume");}
    private void ensureProfile(ObjectNode project,String providerVoiceId){if(!project.path("testRun").asBoolean()||!"PIPELINE".equalsIgnoreCase(text(project,"testPhase"))||!"phase-b-production".equals(text(project,"scenarioId"))||!"A1".equals(text(project,"assetDependencyLevel"))||!providerVoiceId.equals(text(project,"canaryTtsVoiceId")))throw new WorkflowException("PIPELINE_EXECUTION_PROFILE_MISMATCH","已存在的 Pipeline Canary 项目与当前 Phase B 执行档位不一致，禁止静默继续");}
    private void ids(ArrayNode target,List<ObjectNode> values){values.forEach(value->target.add(id(value)));}
    private List<ObjectNode> selected(String projectId,ResourceKind kind){return store.list(kind,projectId,null).stream().filter(value->value.path("selected").asBoolean()&&value.path("locked").asBoolean()).toList();}
    private List<ObjectNode> ordered(ResourceKind kind,String projectId,String parentId,String field){List<ObjectNode> values=new ArrayList<>(store.list(kind,projectId,parentId));values.sort(Comparator.comparingInt(value->value.path(field).asInt(Integer.MAX_VALUE)));return values;}
    private List<ObjectNode> orderedShots(String projectId){List<ObjectNode> result=new ArrayList<>();for(ObjectNode episode:ordered(EPISODE,projectId,null,"episodeNo"))for(ObjectNode scene:ordered(SCENE,projectId,id(episode),"sceneNo"))result.addAll(ordered(SHOT,projectId,id(scene),"shotNo"));return result.stream().filter(value->!value.path("stale").asBoolean()).toList();}
}
