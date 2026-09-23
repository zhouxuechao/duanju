package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.workflow.LiveCanaryState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.yourapp.drama.workflow.Documents.obj;

/** Re-evaluates an existing paid canary using local evidence only. */
public final class ProviderCanaryReconciler {
    private final ObjectMapper mapper;
    private final ProviderCapabilityRegistry capabilities;

    public ProviderCanaryReconciler(ObjectMapper mapper,ProviderCapabilityRegistry capabilities){
        this.mapper=mapper;this.capabilities=capabilities;
    }

    public Result reconcile(Path evidencePath,Path statePath,Path snapshotPath,Path reconciledEvidencePath,Path reportDirectory) throws Exception {
        ObjectNode evidence=(ObjectNode)mapper.readTree(evidencePath.toFile());
        LiveCanaryState state=LiveCanaryState.open(statePath);ObjectNode before=state.snapshot();
        String runId=required(evidence,"runId"),requestId=required(evidence.path("video"),"providerRequestId"),taskId=required(evidence.path("video"),"providerTaskId");
        requireIdentity("runId",runId,required(before,"runId"));
        requireIdentity("providerRequestId",requestId,required(before,"providerRequestId"));
        requireIdentity("providerTaskId",taskId,required(before,"providerTaskId"));
        if(!"RECONCILIATION_REQUIRED".equals(required(before,"status")))throw new IllegalArgumentException("Canary state is not eligible for offline reconciliation");
        if(!"SUCCEEDED".equals(required(evidence.path("video"),"providerStatus")))throw new IllegalArgumentException("Provider task is not definitively successful");
        if(evidence.path("retryCount").asInt(-1)!=0)throw new IllegalArgumentException("Offline reconciliation requires retryCount=0");

        ObjectNode snapshot=new ProviderCapabilityContract().verifyCanary(
                capabilities.imageProfile(required(evidence.path("image"),"modelId")),
                capabilities.profile(required(evidence.path("video"),"modelId")),evidence);
        if(!"PARTIAL_LIVE_VERIFIED".equals(snapshot.path("verificationStatus").asText())||!snapshot.path("failures").isEmpty())
            throw new IllegalArgumentException("Existing evidence did not pass capability verification");

        Instant reconciledAt=Instant.now();double settledEstimate=evidence.path("budget").path("actualCost").asDouble(0);
        ObjectNode replay=obj().put("runId",runId).put("status","SUCCEEDED")
                .put("originalCanaryStatus",before.path("status").asText()).put("reconciliationMode","OFFLINE_EVIDENCE_REPLAY")
                .put("reconciledAt",reconciledAt.toString()).put("providerRequestsDuringReconciliation",0)
                .put("newRealImageRequests",0).put("newRealVideoSubmissions",0).put("retryCount",evidence.path("retryCount").asInt())
                .put("billingStatus",evidence.path("billingStatus").asText("UNPRICED")).put("providerActualCost","UNKNOWN")
                .put("budgetSettledEstimate",settledEstimate).put("budgetSettledEstimateIsProviderBilling",false)
                .put("providerRequestId",requestId).put("providerTaskId",taskId);
        replay.set("image",evidence.path("image").deepCopy());replay.set("video",evidence.path("video").deepCopy());replay.set("capabilitySnapshot",snapshot.deepCopy());

        Files.createDirectories(snapshotPath.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(snapshotPath.toFile(),snapshot);
        Files.createDirectories(reconciledEvidencePath.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(reconciledEvidencePath.toFile(),replay);
        Files.createDirectories(reportDirectory);
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC).format(reconciledAt);
        Path report=reportDirectory.resolve("provider-canary-reconciliation-"+timestamp+".md");
        Files.writeString(report,report(replay,snapshot,evidencePath),StandardCharsets.UTF_8);
        state.advance("SUCCEEDED",requestId,taskId);
        return new Result(snapshot,state.snapshot(),reconciledEvidencePath,report);
    }

    private String report(ObjectNode replay,ObjectNode snapshot,Path originalEvidence){
        var image=snapshot.path("imageOutputs").path("2K").path("9:16");var video=snapshot.path("videoOutputs").path("480p").path("5s");
        return "# Provider Canary Offline Reconciliation\n\n"
                +"- Original run ID: `"+replay.path("runId").asText()+"`\n"
                +"- Original canary status: `"+replay.path("originalCanaryStatus").asText()+"`\n"
                +"- Reconciliation mode: `OFFLINE_EVIDENCE_REPLAY`\n"
                +"- Reconciled status: `SUCCEEDED`\n"
                +"- Capability overall: `"+snapshot.path("verificationStatus").asText()+"`\n"
                +"- Image 2K/9:16: `"+image.path("status").asText()+"`\n"
                +"- Video 480p/5s/9:16: `"+video.path("status").asText()+"`\n"
                +"- Observed video geometry: `"+video.path("actualWidth").asInt()+" × "+video.path("actualHeight").asInt()+"`\n"
                +"- Provider requests during reconciliation: `0`\n"
                +"- Billing: `"+replay.path("billingStatus").asText()+"`\n"
                +"- Budget settled estimate: `"+replay.path("budgetSettledEstimate").asDouble()+"` (not Provider billing)\n"
                +"- Provider actual cost: `UNKNOWN`\n"
                +"- Original evidence: `"+originalEvidence.toString().replace('\\','/')+"`\n";
    }

    private void requireIdentity(String field,String evidence,String state){if(!evidence.equals(state))throw new IllegalArgumentException("Canary identity mismatch: "+field);}
    private String required(com.fasterxml.jackson.databind.JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new IllegalArgumentException("Canary evidence identity is missing: "+field);return value;}

    public record Result(ObjectNode capability,ObjectNode state,Path reconciledEvidence,Path report){}
}
