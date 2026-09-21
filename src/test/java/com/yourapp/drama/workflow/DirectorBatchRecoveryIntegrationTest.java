package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.DirectorGenerationService;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties="drama.director.mode=live")
@ActiveProfiles("test")
@Transactional
class DirectorBatchRecoveryIntegrationTest {
    @Autowired DocumentStore store;@Autowired WorkflowService workflow;@Autowired AssetViewService assets;@Autowired GenerationWorker worker;
    @Autowired JobService jobs;@Autowired DirectorGenerationService director;@Autowired ObjectMapper mapper;@MockitoBean LlmGateway llm;
    private String projectId,sceneId;private final AtomicInteger planCalls=new AtomicInteger(),detailCalls=new AtomicInteger(),batch2Calls=new AtomicInteger();

    @BeforeEach void setup(){
        ObjectNode projectInput=obj().put("name","批次恢复").put("idea","农村夜间异响").put("dialect","MANDARIN").put("episodeCount",1);
        projectInput.set("sceneContinuityPolicy",obj().put("maxContinuationDepth",4).put("resetAtSceneBoundary",true).put("reanchorFromCanonical",true).put("reanchorOnIdentityDrift",true).put("reanchorOnLocationDrift",true));
        ObjectNode project=store.create(PROJECT,projectInput);projectId=id(project);
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",projectId).put("name","第一集").put("continuityHash","fixture-core").put("script","老人沿院墙寻找异响来源"));
        ObjectNode actor=store.create(CHARACTER,obj().put("projectId",projectId).put("characterKey","elder").put("name","周伯").put("provider","SEEDREAM").put("sourceType","IMAGE_REFERENCE").put("providerStatus","UNBOUND"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("characterId",id(actor)).put("name","蓝棉袄").put("description","旧蓝棉袄黑布鞋"));actor=store.update(CHARACTER,id(actor),revision(actor),actor.deepCopy().put("baseLookId",id(look)));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",projectId).put("locationKey","yard").put("name","院落").put("description","北门东井"));NewWorkflowTestFixtures.install(store,assets,project,episode,actor,look,location);
        ObjectNode ep=store.get(EPISODE,id(episode));String coreId=text(ep,"storyBibleId");ObjectNode content=obj().put("script","老人沿院墙寻找异响来源");content.putArray("characterKeys").add("elder");content.putArray("locationKeys").add("yard");content.putArray("propKeys");
        ObjectNode script=store.create(STORY_DOCUMENT,obj().put("projectId",projectId).put("coreId",coreId).put("documentType","EPISODE_SCRIPT").put("reviewStatus","CONFIRMED").put("version",2).set("content",content));store.update(EPISODE,id(ep),revision(ep),ep.deepCopy().put("storyDocumentId",id(script)));
        sceneId=id(store.create(SCENE,obj().put("projectId",projectId).put("episodeId",id(episode)).put("name","院落追声").put("description","老人沿院墙寻找异响来源").put("duration",30)));
        when(llm.generate(any(),eq(JsonNode.class))).thenAnswer(invocation->{LlmGateway.StructuredRequest request=invocation.getArgument(0);assertThat(request.options()).doesNotContainKey("stage");ObjectNode input=(ObjectNode)mapper.readTree(request.userPrompt());String stage=input.path("stage").asText();JsonNode value;
            if("DIRECTOR_PLAN".equals(stage)){planCalls.incrementAndGet();assertThat(input.path("sceneTargetDurationSeconds").asDouble()).isEqualTo(30);assertThat(input.path("scene").has("duration")).isFalse();assertThat(input.path("project").has("targetDuration")).isFalse();assertThat(input.path("episodeFormat").path("profileId").asText()).isEqualTo("GENERAL_MICRO");value=ReflectionTestUtils.invokeMethod(director,"demoPlan",input);}
            else {detailCalls.incrementAndGet();assertThat(input.path("shotSkeletons")).hasSize(1);int first=input.path("shotSkeletons").path(0).path("shotIndex").asInt();if(first==5&&batch2Calls.incrementAndGet()==1)throw new ProviderException("OUTPUT_TRUNCATED","第五镜输出截断","req-shot-5",200,false,false).withRawOutput("{\"shots\":[").withProviderDiagnostics("length",900,16384,17284);value=ReflectionTestUtils.invokeMethod(director,"demoDetail",input);}
            return new LlmGateway.StructuredResult<>(value,"fake-director","req-"+stage+"-"+(planCalls.get()+detailCalls.get()),value.toString(),true,new LlmGateway.ProviderUsage("stop",600,900,1500));});
    }

    @Test void failedSecondBatchRetriesOnlyThatBatchAndResumesFromBatchOneCheckpoint(){
        ObjectNode root=workflow.plan(sceneId,obj());worker.tick();worker.tick();
        ObjectNode batch1=detailJobs().getFirst();assertThat(text(batch1,"status")).isEqualTo("SUCCESS");ObjectNode checkpoint=batch1.path("outputSnapshot").deepCopy();
        for(int i=0;i<4;i++)worker.tick();ObjectNode failed=detailJobs().stream().filter(j->j.path("inputSnapshot").path("shotStart").asInt()==5).findFirst().orElseThrow();
        assertThat(text(failed,"status")).isEqualTo("FAILED");assertThat(text(failed,"failureCode")).isEqualTo("OUTPUT_TRUNCATED");
        ObjectNode retry=jobs.retry(id(failed));assertThat(retry.path("inputSnapshot").path("retryOfJobId").asText()).isEqualTo(id(failed));
        assertThat(id(jobs.retry(id(failed)))).isEqualTo(id(retry));
        for(int i=0;i<16&&store.list(SHOT,projectId,sceneId).isEmpty();i++)worker.tick();
        assertThat(planCalls.get()).isEqualTo(1);assertThat(batch2Calls.get()).isEqualTo(2);assertThat(store.get(GENERATION_JOB,id(batch1)).path("outputSnapshot")).isEqualTo(checkpoint);
        assertThat(detailJobs().stream().filter(j->"SUCCESS".equals(text(j,"status"))&&j.path("inputSnapshot").path("batchIndex").asInt()==1)).hasSize(1);
        List<ObjectNode> shots=store.list(SHOT,projectId,sceneId).stream().sorted(java.util.Comparator.comparingInt(s->s.path("shotNo").asInt())).toList();
        assertThat(shots).hasSize(10);assertThat(store.get(GENERATION_JOB,id(root)).path("outputSnapshot").path("detailsComplete").asBoolean()).isTrue();
        assertThat(text(shots.getFirst(),"sequenceRelation")).isEqualTo("SEQUENCE_FIRST_CLIP");
        assertThat(text(shots.get(1),"sequenceRelation")).isEqualTo("SEAMLESS_CONTINUATION");
        assertThat(text(shots.get(2),"sequenceRelation")).isEqualTo("INTENTIONAL_NEXT_SHOT");
        for(ObjectNode shot:shots){String current=text(shot.path("currentBeat"),"beatId");List<String> completed=new java.util.ArrayList<>(),reserved=new java.util.ArrayList<>();shot.path("completedBeats").forEach(v->completed.add(v.asText()));shot.path("reservedFutureBeats").forEach(v->reserved.add(v.asText()));assertThat(completed).doesNotContain(current);assertThat(reserved).doesNotContain(current);}
        assertThat(store.get(SCENE,sceneId).path("sceneContinuityPolicy").path("maxContinuationDepth").asInt()).isEqualTo(4);
    }

    @Test void completedMalformedPlanIsRejectedAndRetriedThroughTheStrictProviderContract(){
        reset(llm);AtomicInteger providerPlanCalls=new AtomicInteger();
        when(llm.generate(any(),eq(JsonNode.class))).thenAnswer(invocation->{
            LlmGateway.StructuredRequest request=invocation.getArgument(0);ObjectNode input=(ObjectNode)mapper.readTree(request.userPrompt());String stage=input.path("stage").asText();
            JsonNode value=ReflectionTestUtils.invokeMethod(director,"DIRECTOR_PLAN".equals(stage)?"demoPlan":"demoDetail",input);
            if("DIRECTOR_PLAN".equals(stage)){
                int call=providerPlanCalls.incrementAndGet();
                String marker="\"averageShotLength\":3.0",raw=value.toString();assertThat(raw).contains(marker);
                if(call==1)throw new ProviderException("INVALID_STRUCTURED_OUTPUT","单个数字后多余引号","req-malformed",200,false,false).withRawOutput(raw.replace(marker,marker+"\""));
            }
            return new LlmGateway.StructuredResult<>(value,"fake-director","req-detail",value.toString(),true,new LlmGateway.ProviderUsage("stop",300,500,800));
        });
        ObjectNode failed=workflow.plan(sceneId,obj().put("requestKey","malformed-first"));worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(failed)),"failureCode")).isEqualTo("INVALID_STRUCTURED_OUTPUT");
        ObjectNode recovered=workflow.plan(sceneId,obj().put("requestKey","malformed-local-recovery"));
        assertThat(text(recovered.path("inputSnapshot"),"retryOfJobId")).isEqualTo(id(failed));
        for(int i=0;i<16&&store.list(SHOT,projectId,sceneId).isEmpty();i++)worker.tick();
        assertThat(providerPlanCalls.get()).isEqualTo(2);
        assertThat(store.list(SHOT,projectId,sceneId)).hasSize(10);
        assertThat(store.get(GENERATION_JOB,id(recovered)).path("outputSnapshot").path("detailsComplete").asBoolean()).isTrue();
    }

    @Test void completedProviderOutputThatFailedABusinessRuleIsAlsoRevalidatedLocally(){
        ObjectNode failed=workflow.plan(sceneId,obj().put("requestKey","business-rule-first"));ObjectNode providerInput=ReflectionTestUtils.invokeMethod(director,"planInput",failed.path("inputSnapshot"));
        ObjectNode providerOutput=ReflectionTestUtils.invokeMethod(director,"demoPlan",providerInput);ObjectNode stored=store.get(GENERATION_JOB,id(failed));
        store.update(GENERATION_JOB,id(stored),revision(stored),stored.deepCopy().put("status","FAILED").put("failureCode","GENERATION_FAILED").put("providerRequestId","req-business-rule").set("providerOutput",providerOutput));
        reset(llm);AtomicInteger providerPlanCalls=new AtomicInteger();when(llm.generate(any(),eq(JsonNode.class))).thenAnswer(invocation->{
            LlmGateway.StructuredRequest request=invocation.getArgument(0);ObjectNode input=(ObjectNode)mapper.readTree(request.userPrompt());if("DIRECTOR_PLAN".equals(input.path("stage").asText())){providerPlanCalls.incrementAndGet();throw new AssertionError("已持久化的根输出不应再次调用三方");}
            JsonNode value=ReflectionTestUtils.invokeMethod(director,"demoDetail",input);return new LlmGateway.StructuredResult<>(value,"fake-director","req-detail",value.toString(),true,new LlmGateway.ProviderUsage("stop",300,500,800));
        });
        ObjectNode recovered=workflow.plan(sceneId,obj().put("requestKey","business-rule-local-recovery"));assertThat(text(recovered.path("inputSnapshot"),"retryOfJobId")).isEqualTo(id(failed));
        for(int i=0;i<16&&store.list(SHOT,projectId,sceneId).isEmpty();i++)worker.tick();
        assertThat(providerPlanCalls.get()).isZero();assertThat(store.list(SHOT,projectId,sceneId)).hasSize(10);
    }

    @Test void completedPlanWithAFailedDetailCanRestartFromItsValidatedPlanWithoutRegeneratingThePlan(){
        ObjectNode first=workflow.plan(sceneId,obj().put("requestKey","first-detail-attempt"));
        worker.tick();
        for(int i=0;i<5;i++)worker.tick();
        assertThat(detailJobs().stream().anyMatch(j->"FAILED".equals(text(j,"status")))).isTrue();

        ObjectNode replacement=workflow.plan(sceneId,obj().put("requestKey","single-shot-restart"));
        assertThat(id(replacement)).isNotEqualTo(id(first));
        assertThat(text(replacement.path("inputSnapshot"),"retryOfJobId")).isEqualTo(id(first));
    }

    @Test void manuallyRetryingASchemaValidDetailThatFailedABusinessRuleRequestsANewProviderOutput(){
        workflow.plan(sceneId,obj().put("requestKey","detail-business-rule"));worker.tick();
        ObjectNode detail=detailJobs().getFirst();ObjectNode output=ReflectionTestUtils.invokeMethod(director,"demoDetail",detail.path("inputSnapshot"));
        ObjectNode stored=store.get(GENERATION_JOB,id(detail));store.update(GENERATION_JOB,id(stored),revision(stored),stored.deepCopy()
            .put("status","FAILED").put("failureCode","GENERATION_FAILED").put("providerRequestId","req-valid-detail").set("providerOutput",output));
        ObjectNode retry=jobs.retry(id(detail));
        reset(llm);AtomicInteger providerCalls=new AtomicInteger();when(llm.generate(any(),eq(JsonNode.class))).thenAnswer(invocation->{providerCalls.incrementAndGet();LlmGateway.StructuredRequest request=invocation.getArgument(0);ObjectNode input=(ObjectNode)mapper.readTree(request.userPrompt());JsonNode value=ReflectionTestUtils.invokeMethod(director,"demoDetail",input);return new LlmGateway.StructuredResult<>(value,"fake-director","unexpected-detail-call",value.toString(),true,new LlmGateway.ProviderUsage("stop",1,1,2));});
        worker.tick();
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(text(store.get(GENERATION_JOB,id(retry)),"status")).isEqualTo("SUCCESS");
    }

    @Test void localRevalidationUsesThePersistedSchemaValidOutputWithoutCallingTheProvider(){
        workflow.plan(sceneId,obj().put("requestKey","detail-local-revalidation"));worker.tick();
        ObjectNode detail=detailJobs().getFirst();ObjectNode output=ReflectionTestUtils.invokeMethod(director,"demoDetail",detail.path("inputSnapshot"));
        ObjectNode stored=store.get(GENERATION_JOB,id(detail));store.update(GENERATION_JOB,id(stored),revision(stored),stored.deepCopy()
            .put("status","FAILED").put("failureCode","GENERATION_FAILED").put("providerRequestId","req-valid-detail").set("providerOutput",output));
        ObjectNode revalidation=ReflectionTestUtils.invokeMethod(jobs,"revalidate",id(detail));
        reset(llm);AtomicInteger providerCalls=new AtomicInteger();when(llm.generate(any(),eq(JsonNode.class))).thenAnswer(invocation->{providerCalls.incrementAndGet();throw new AssertionError("本地复核不应调用三方");});
        worker.tick();
        assertThat(providerCalls.get()).isZero();
        assertThat(text(store.get(GENERATION_JOB,id(revalidation)),"status")).isEqualTo("SUCCESS");
    }

    private List<ObjectNode> detailJobs(){return store.list(GENERATION_JOB,projectId,null).stream().filter(j->"SHOT_DETAIL".equals(text(j,"type"))).toList();}
}
