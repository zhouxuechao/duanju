package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.*;
import com.yourapp.drama.model.ProviderException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class AutomaticVideoReviewService {
    private static final Object[] LOCKS=new Object[64];static{Arrays.setAll(LOCKS,ignored->new Object());}
    private final DocumentStore store;private final VisualExpectedContextService expectedContexts;private final VideoQualityReviewer reviewer;private final RuleEngine rules;private final VisualQualityProtocol protocol;private final VisualQualityPolicy policy;private final VisualReviewMediaService media;private final VideoFrameExtractor extractor;private final WorkflowService workflow;
    public AutomaticVideoReviewService(DocumentStore store,VisualExpectedContextService expectedContexts,VideoQualityReviewer reviewer,RuleEngine rules,VisualQualityProtocol protocol,VisualQualityPolicy policy,VisualReviewMediaService media,VideoFrameExtractor extractor,WorkflowService workflow){this.store=store;this.expectedContexts=expectedContexts;this.reviewer=reviewer;this.rules=rules;this.protocol=protocol;this.policy=policy;this.media=media;this.extractor=extractor;this.workflow=workflow;}

    public ObjectNode review(String takeId,JsonNode request){
        String assessmentKey="vlm:video:v"+VisualQualityProtocol.VERSION+":"+takeId+(request.path("apply").asBoolean(false)?":apply":":shadow");synchronized(LOCKS[Math.floorMod(assessmentKey.hashCode(),LOCKS.length)]){return reviewOnce(takeId,request,assessmentKey);}
    }
    private ObjectNode reviewOnce(String takeId,JsonNode request,String assessmentKey){
        ObjectNode take=store.get(VIDEO_TAKE,takeId);if(!"SUCCEEDED".equals(text(take,"providerStatus")))throw new WorkflowException("VIDEO_NOT_READY","视频尚未生成成功");
        ObjectNode existing=existingAssessment(project(take),takeId,assessmentKey);if(existing!=null){ObjectNode response=take.deepCopy();response.set("automaticReview",existing);return response;}boolean shadow=!request.path("apply").asBoolean(false);
        JsonNode generation=take.path("inputSnapshot");if(!generation.path("context").isObject())throw new WorkflowException("VIDEO_CONTEXT_REQUIRED","视频缺少生成时上下文，不能进行可靠质检");
        try{rules.requireDeterministicPass(generation.path("context"));}catch(DeterministicRuleViolationException failure){throw new WorkflowException("DETERMINISTIC_RULE_FAILED",failure.getMessage());}
        ObjectNode expected=expectedContexts.build(generation.path("context"));media.addReferences(take,generation,expected);
        List<VideoQualityReviewer.Frame> frames=take.path("simulated").asBoolean()?simulatedFrames():extractor.extract(media.requiredArchiveKey(take));
        JsonNode result;try{result=protocol.validate(reviewer.review(expected,frames),expected);}catch(ProviderException failure){failedAssessment(take,assessmentKey,shadow,failure);throw failure;}
        VisualQualityPolicy.Decision route=policy.decide(result,expected,priorProviderDecisions(takeId));
        if(route==VisualQualityPolicy.Decision.AUTO_REGENERATE&&reachesRepairLimit(take,result))route=VisualQualityPolicy.Decision.MANUAL_REVIEW;
        ObjectNode body=body(expected,result,route).put("assessmentKey",assessmentKey);
        if(shadow||route==VisualQualityPolicy.Decision.MANUAL_REVIEW)return assessment(take,body,shadow,route);
        ObjectNode reviewed=workflow.review(VIDEO_TAKE,takeId,body);
        if(route==VisualQualityPolicy.Decision.AUTO_REGENERATE){
            ObjectNode response=reviewed.deepCopy();
            String repairKey="vlm-video-repair:"+assessmentKey;
            response.set("automaticRepairJob",workflow.video(required(take,"sourceKeyframeId"),obj().put("requestKey",repairKey)));
            return response;
        }
        return reviewed;
    }
    private ObjectNode body(ObjectNode expected,JsonNode result,VisualQualityPolicy.Decision route){
        boolean passed=route==VisualQualityPolicy.Decision.AUTO_PASS;String decision=passed?"PASS":route==VisualQualityPolicy.Decision.AUTO_REGENERATE?"REGENERATE":"MANUAL_FIX";ObjectNode body=obj().put("passed",passed).put("decision",decision).put("reviewer","AUTOMATIC").put("failureOrigin",result.path("failureOriginHint").asText(passed?"UNKNOWN":"PROVIDER_OUTPUT")).put("failureReason",result.path("reason").asText()).put("notes",result.path("reason").asText()).put("score",result.path("overallScore").asDouble()).put("visualQuality",result.path("overallScore").asDouble());
        body.put("characterConsistency",score(result,"characterIdentity")).put("clothingConsistency",score(result,"clothingConsistency")).put("locationConsistency",score(result,"locationConsistency")).put("propConsistency",score(result,"propConsistency")).put("composition",score(result,"compositionQuality")).put("actionAccuracy",score(result,"actionAccuracy")).put("styleConsistency",score(result,"styleConsistency")).put("motionContinuity",score(result,"continuityWithPreviousShot"));body.set("failureCodes",result.path("failureCodes").deepCopy());body.set("expectedContext",media.compactExpected(expected));body.set("qualityReviewResult",result.deepCopy());if(passed){body.set("observedStartState",expected.path("requiredConstraints").path("shotStart").deepCopy());body.set("observedState",expected.path("requiredConstraints").path("shotEnd").deepCopy());}return body;
    }
    private ObjectNode assessment(ObjectNode take,ObjectNode body,boolean shadow,VisualQualityPolicy.Decision route){
        ObjectNode qc=obj().put("projectId",project(take)).put("targetKind",VIDEO_TAKE.path()).put("targetId",id(take)).put("shotId",required(take,"shotId")).put("reviewSequence",nextReviewSequence(take)).put("passed",body.path("passed").asBoolean()).put("score",body.path("score").asDouble()).put("reviewer","AUTOMATIC").put("decision",text(body,"decision")).put("routingDecision",route.name()).put("shadow",shadow).put("assessmentKey",text(body,"assessmentKey")).put("manualReviewRequired",route==VisualQualityPolicy.Decision.MANUAL_REVIEW).put("notes",text(body,"notes"));qc.set("failureCodes",body.path("failureCodes").deepCopy());qc.set("expectedContext",body.path("expectedContext").deepCopy());qc.set("qualityReviewResult",body.path("qualityReviewResult").deepCopy());String providerRequestId=text(body.path("qualityReviewResult").path("_provider"),"requestId");if(!providerRequestId.isBlank())qc.put("providerRequestId",providerRequestId);ObjectNode saved=store.create(QC_RESULT,qc);ObjectNode response=take.deepCopy();response.set("automaticReview",saved);return response;
    }
    private List<String> priorProviderDecisions(String targetId){List<String> values=new ArrayList<>();for(ObjectNode qc:store.list(QC_RESULT,null,null))if(targetId.equals(text(qc,"targetId"))&&"AUTOMATIC".equals(text(qc,"reviewer"))){String decision=text(qc.path("qualityReviewResult"),"decision");if(!decision.isBlank())values.add(decision);}return values.isEmpty()?values:List.of(values.getLast());}
    private boolean reachesRepairLimit(ObjectNode take,JsonNode result){
        String currentCode=result.path("failureCodes").path(0).asText();if(currentCode.isBlank())return false;
        Map<String,ObjectNode> latestByTake=new LinkedHashMap<>();
        for(ObjectNode qc:store.list(QC_RESULT,project(take),null))if(required(take,"shotId").equals(text(qc,"shotId"))&&VIDEO_TAKE.path().equals(text(qc,"targetKind"))&&!qc.path("shadow").asBoolean())latestByTake.put(text(qc,"targetId"),qc);
        List<ObjectNode> previous=new ArrayList<>(latestByTake.values());previous.sort(Comparator.comparing(Documents::createdAt).reversed());
        int consecutive=1;
        for(ObjectNode qc:previous){String code=qc.path("diagnosis").path("failureCodes").path(0).asText(qc.path("failureCodes").path(0).asText());if(qc.path("passed").asBoolean()||!currentCode.equals(code))break;if(++consecutive>=3)return true;}
        return false;
    }
    private ObjectNode existingAssessment(String projectId,String targetId,String assessmentKey){for(ObjectNode qc:store.list(QC_RESULT,projectId,null))if(targetId.equals(text(qc,"targetId"))&&assessmentKey.equals(text(qc,"assessmentKey"))&&"AUTOMATIC".equals(text(qc,"reviewer")))return qc;return null;}
    private void failedAssessment(ObjectNode take,String assessmentKey,boolean shadow,ProviderException failure){ObjectNode qc=obj().put("projectId",project(take)).put("targetKind",VIDEO_TAKE.path()).put("targetId",id(take)).put("shotId",required(take,"shotId")).put("reviewSequence",nextReviewSequence(take)).put("reviewer","AUTOMATIC").put("assessmentKey",assessmentKey).put("assessmentStatus","FAILED").put("shadow",shadow).put("passed",false).put("decision","MANUAL_FIX").put("routingDecision","MANUAL_REVIEW").put("failureCode",failure.code()).put("failureReason",failure.getMessage()==null?"视频视觉模型请求失败":failure.getMessage()).put("retryable",failure.retryable()).put("submissionUncertain",failure.uncertain());if(failure.requestId()!=null&&!failure.requestId().isBlank())qc.put("providerRequestId",failure.requestId());if(failure.rawOutput()!=null)qc.put("providerOutputRaw",bounded(failure.rawOutput()));store.create(QC_RESULT,qc);}
    private String bounded(String value){return value.length()<=16000?value:value.substring(0,16000);}
    private List<VideoQualityReviewer.Frame> simulatedFrames(){List<VideoQualityReviewer.Frame> frames=new ArrayList<>();for(int i=0;i<5;i++)frames.add(new VideoQualityReviewer.Frame(i,"data:image/jpeg;base64,/9j/2Q=="));return List.copyOf(frames);}
    private long nextReviewSequence(ObjectNode take){return store.list(QC_RESULT,project(take),null).stream().filter(review->id(take).equals(text(review,"targetId"))).mapToLong(review->review.path("reviewSequence").asLong(0)).max().orElse(0)+1;}
    private double score(JsonNode result,String metric){return result.path(metric).path("score").asDouble();}
}
