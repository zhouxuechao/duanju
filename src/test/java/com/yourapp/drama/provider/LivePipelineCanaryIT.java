package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.LiveCanaryPlan;
import com.yourapp.drama.workflow.PipelineCanaryCapabilityGate;
import com.yourapp.drama.workflow.PipelineCanaryFixture;
import com.yourapp.drama.workflow.PipelineCanaryProductionAdapter;
import com.yourapp.drama.workflow.PipelineCanaryProductionGate;
import com.yourapp.drama.workflow.PipelineCanaryState;
import com.yourapp.drama.workflow.PipelineLiveExecutionProfile;
import com.yourapp.drama.workflow.TestBudgetGuard;
import com.yourapp.drama.workflow.WorkflowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled Phase B entry point. It delegates every business stage to the production workflow. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="drama.jobs.enabled=false")
class LivePipelineCanaryIT {
    @Autowired PipelineCanaryProductionAdapter production;
    @Autowired ObjectMapper mapper;
    @Autowired TestBudgetGuard budgetGuard;

    @Test
    @EnabledIfEnvironmentVariable(named="RUN_LIVE_PIPELINE_CANARY",matches="(?i)true")
    void runsOrResumesTheProductionPipelineWithoutOwningProviderOrEditingLogic() throws Exception {
        LiveCanaryPlan.pipeline().requireLiveFlags(System.getenv());
        new PipelineCanaryCapabilityGate(mapper).requireReady(Path.of("target","provider-capability-snapshot.json"));
        new PipelineCanaryProductionGate().requireReady(production);
        ObjectNode preflight=LiveCanaryPlan.pipeline().validateLive(System.getenv(),List.of());
        assertThat(preflight.path("ready").asBoolean()).as(preflight.path("blocking").toString()).isTrue();

        PipelineCanaryFixture fixture=PipelineCanaryFixture.standard();
        Path root=Path.of("target","live-canary"),statePath=root.resolve("pipeline-canary-state.json"),evidencePath=root.resolve("pipeline-canary-evidence.json");
        PipelineCanaryState state=Files.exists(statePath)?PipelineCanaryState.open(statePath):PipelineCanaryState.start(
                statePath,optional("DRAMA_TEST_RUN_ID","pipeline-canary-"+UUID.randomUUID()),gitCommit(),fixture.shots().stream().map(PipelineCanaryFixture.ShotPlan::shotId).toList());
        ObjectNode result;
        try{
            ObjectNode snapshot=state.snapshot();
            ObjectNode cursor=snapshot.path("production").isObject()?(ObjectNode)snapshot.path("production"):obj();
            if(cursor.path("pipelineRunId").asText().isBlank())result=production.start(fixture,snapshot.path("runId").asText(),"CANARY");
            else{result=production.reconcile(cursor);if(!"SUCCESS".equals(result.path("status").asText()))result=production.resume(result.path("pipelineRunId").asText());}
            state.productionSnapshot(result);
        }catch(WorkflowException error){
            if("RECONCILIATION_REQUIRED".equals(error.code()))state.productionReconciliationRequired(error.code());else state.localFailure(error.code());
            writeEvidence(evidencePath,state.snapshot());throw error;
        }
        writeEvidence(evidencePath,state.snapshot());
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("SUCCESS");
        assertThat(state.snapshot().path("status").asText()).isEqualTo("SUCCEEDED");
    }

    private void writeEvidence(Path path,ObjectNode state) throws Exception {
        ObjectNode productionCursor=(ObjectNode)state.path("production");
        String runId=state.path("runId").asText();ObjectNode budgetSnapshot=budgetGuard.snapshot(runId),jobCounts=productionCursor.path("jobCounts").isObject()?(ObjectNode)productionCursor.path("jobCounts"):obj();
        ObjectNode evidence=obj().put("runId",state.path("runId").asText()).put("commit",state.path("commit").asText())
                .put("status",state.path("status").asText()).put("checkedAt",Instant.now().toString()).put("source","PRODUCTION_WORKFLOW");
        evidence.set("production",productionCursor.deepCopy());
        evidence.set("executionProfile",PipelineLiveExecutionProfile.phaseB().toJson());evidence.set("jobCounts",jobCounts.deepCopy());evidence.set("llmStageCounts",productionCursor.path("llmStageCounts").deepCopy());evidence.set("budgetSnapshot",budgetSnapshot.deepCopy());
        int images=jobCounts.path("ASSET_IMAGE").asInt()+jobCounts.path("KEYFRAME").asInt(),videos=jobCounts.path("VIDEO").asInt(),audio=jobCounts.path("TTS").asInt();
        ObjectNode budget=obj().put("maxCost",decimal("PIPELINE_TEST_MAX_COST_CNY")).put("billingStatus","UNPRICED").put("providerActualCost","UNKNOWN")
                .put("budgetSettledEstimate",budgetSnapshot.path("actualCost").asDouble()).put("plannedCost",budgetSnapshot.path("plannedCost").asDouble())
                .put("reservedCost",budgetSnapshot.path("reservedCost").asDouble()).put("wasteCost",budgetSnapshot.path("wasteCost").asDouble())
                .put("realImageRequests",images).put("realVideoSubmissions",videos).put("realAudioRequests",audio).put("retryCount",0);
        for(String field:List.of("llm","vlm","keyframeQc","videoQc","image","video","tts","lipsync"))budget.set(field,budgetSnapshot.path(field).deepCopy());
        evidence.set("budget",budget);
        Files.createDirectories(path.toAbsolutePath().getParent());mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
    }

    private String gitCommit() throws Exception {Process process=new ProcessBuilder("git","rev-parse","HEAD").redirectErrorStream(true).start();String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(!process.waitFor(10,TimeUnit.SECONDS)||process.exitValue()!=0||!value.matches("[a-f0-9]{40}"))throw new IllegalStateException("Git commit cannot be resolved");return value;}
    private static String optional(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
    private static double decimal(String name){try{return Double.parseDouble(optional(name,"0"));}catch(NumberFormatException error){return 0;}}
}
