package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class QualityMetricsService {
    private final DocumentStore store;
    public QualityMetricsService(DocumentStore store){this.store=store;}

    public ObjectNode metrics(String projectId){
        List<ObjectNode> frames=store.list(KEYFRAME,projectId,null);
        Map<String,List<ObjectNode>> byShot=new HashMap<>();
        for(ObjectNode frame:frames)byShot.computeIfAbsent(text(frame,"shotId"),ignored->new ArrayList<>()).add(frame);
        int reviewedShots=0,firstPass=0,accepted=0,attempts=0;
        for(List<ObjectNode> shotFrames:byShot.values()){
            shotFrames.sort(Comparator.comparingInt(frame->frame.path("version").asInt()));attempts+=shotFrames.size();
            Optional<ObjectNode> firstReviewed=shotFrames.stream().filter(frame->!"PENDING".equals(text(frame,"qcStatus"))).findFirst();
            if(firstReviewed.isPresent()){reviewedShots++;if("PASSED".equals(text(firstReviewed.get(),"qcStatus")))firstPass++;}
            if(shotFrames.stream().anyMatch(frame->"PASSED".equals(text(frame,"qcStatus"))))accepted++;
        }
        Map<String,ObjectNode> latestReviewByAttempt=new HashMap<>();
        for(ObjectNode review:store.list(QC_RESULT,projectId,null)){
            if(review.path("shadow").asBoolean())continue;
            if(!"keyframes".equals(text(review,"targetKind")))continue;
            latestReviewByAttempt.merge(text(review,"targetId"),review,Documents::laterReview);
        }
        int reviewedAttempts=latestReviewByAttempt.size(),identity=0,clothing=0,location=0,prop=0,spatial=0,composition=0,providerOutput=0;
        Set<String> manualShots=new HashSet<>();
        for(ObjectNode review:latestReviewByAttempt.values()){
            if("HUMAN".equals(text(review,"reviewer"))||"MANUAL_FIX".equals(text(review,"decision")))manualShots.add(text(review,"shotId"));
            JsonNode diagnosis=review.path("diagnosis");Set<String> codes=new HashSet<>();diagnosis.path("failureCodes").forEach(code->codes.add(code.asText()));
            if(codes.contains("IDENTITY_MISMATCH"))identity++;if(codes.contains("CLOTHING_MISMATCH"))clothing++;if(codes.contains("LOCATION_MISMATCH"))location++;
            if(codes.contains("PROP_MISMATCH"))prop++;if(codes.contains("POSITION_MISMATCH")||codes.contains("CONTINUITY_MISMATCH"))spatial++;
            if(codes.contains("COMPOSITION_ERROR"))composition++;if("PROVIDER_OUTPUT".equals(text(diagnosis,"failureOrigin")))providerOutput++;
        }
        int providerAttempts=0,providerFailures=0;
        for(ObjectNode job:store.list(GENERATION_JOB,projectId,null))if("KEYFRAME".equals(text(job,"type"))){
            String status=text(job,"status"),code=text(job,"failureCode");boolean reachedProvider="SUCCESS".equals(status)||!text(job,"providerRequestId").isBlank()||code.startsWith("HTTP_")||code.contains("TIMEOUT");
            if(reachedProvider){providerAttempts++;if("FAILED".equals(status))providerFailures++;}
        }
        double totalCost=0,wastedCost=0;Map<String,Double> costByStage=new TreeMap<>(),costByProfile=new TreeMap<>();Map<String,ObjectNode> latestCosts=new LinkedHashMap<>();for(ObjectNode cost:store.list(COST_RECORD,projectId,null)){String key=text(cost,"generationJobId");ObjectNode prior=latestCosts.get(key);if(prior==null||(!cost.path("provisional").asBoolean()&&prior.path("provisional").asBoolean()))latestCosts.put(key,cost);}for(ObjectNode cost:latestCosts.values()){double amount=cost.path("actualCost").asDouble(cost.path("estimatedCost").asDouble());totalCost+=amount;wastedCost+=cost.path("wastedCost").asDouble();costByStage.merge(text(cost,"taskType"),amount,Double::sum);costByProfile.merge(cost.path("generationProfile").asText("TEST"),amount,Double::sum);}
        Map<String,Integer> feedbackReasons=new TreeMap<>();Map<String,Integer> reworkByPrompt=new TreeMap<>();for(ObjectNode feedback:store.list(HUMAN_EDIT_FEEDBACK,projectId,null)){feedback.path("reasonCodes").forEach(reason->feedbackReasons.merge(reason.asText(),1,Integer::sum));String prompt=text(feedback,"promptVersionId");if(!prompt.isBlank())reworkByPrompt.merge(prompt,1,Integer::sum);}
        ObjectNode result=obj().put("reviewedShots",reviewedShots).put("reviewedAttempts",reviewedAttempts)
            .put("firstPassRate",rate(firstPass,reviewedShots)).put("finalPassRate",rate(accepted,byShot.size()))
            .put("averageAttemptsPerKeyframe",rate(attempts,byShot.size())).put("manualInterventionRate",rate(manualShots.size(),reviewedShots))
            .put("identityFailureRate",rate(identity,reviewedAttempts)).put("clothingFailureRate",rate(clothing,reviewedAttempts))
            .put("locationFailureRate",rate(location,reviewedAttempts)).put("propFailureRate",rate(prop,reviewedAttempts)).put("spatialFailureRate",rate(spatial,reviewedAttempts))
            .put("compositionFailureRate",rate(composition,reviewedAttempts)).put("providerOutputQualityFailureRate",rate(providerOutput,reviewedAttempts))
            .put("providerFailureRate",rate(providerFailures,providerAttempts)).put("totalCost",totalCost).put("wastedCost",wastedCost).put("currency","CNY");result.set("costByStage",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.pojoNode(costByStage));result.set("costByGenerationProfile",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.pojoNode(costByProfile));ObjectNode profileMetrics=result.putObject("qualityByGenerationProfile");for(String profile:List.of("TEST","STANDARD","FINAL")){List<ObjectNode> reviews=latestReviewByAttempt.values().stream().filter(review->profile.equals(review.path("generationProfile").asText("TEST"))).toList();profileMetrics.set(profile,obj().put("reviewedAttempts",reviews.size()).put("passRate",rate((int)reviews.stream().filter(review->review.path("passed").asBoolean()).count(),reviews.size())));}result.set("humanEditReasons",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.pojoNode(feedbackReasons));result.set("reworkByPromptVersion",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.pojoNode(reworkByPrompt));return result;
    }
    private double rate(int value,int total){return total==0?0:(double)value/total;}
}
