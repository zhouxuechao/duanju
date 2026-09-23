package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.*;
import com.yourapp.drama.model.ProviderException;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class AutomaticVisualReviewService {
    private static final Object[] LOCKS=new Object[64];static{Arrays.setAll(LOCKS,ignored->new Object());}
    private final DocumentStore store;private final VisualExpectedContextService expectedContexts;private final VisualQualityReviewer reviewer;
    private final RuleEngine rules;private final VisualQualityProtocol protocol;private final VisualQualityPolicy policy;private final VisualReviewMediaService media;private final WorkflowService workflow;private final TestBudgetGuard testBudget;
    public AutomaticVisualReviewService(DocumentStore store,VisualExpectedContextService expectedContexts,VisualQualityReviewer reviewer,RuleEngine rules,VisualQualityProtocol protocol,VisualQualityPolicy policy,VisualReviewMediaService media,WorkflowService workflow,TestBudgetGuard testBudget){
        this.store=store;this.expectedContexts=expectedContexts;this.reviewer=reviewer;this.rules=rules;this.protocol=protocol;this.policy=policy;this.media=media;this.workflow=workflow;this.testBudget=testBudget;
    }

    /** Defaults to shadow assessment. Callers must explicitly set apply=true to affect the production gate. */
    public ObjectNode review(String keyframeId,JsonNode request){
        String assessmentKey=request.path("requestKey").asText("vlm:keyframe:v"+VisualQualityProtocol.VERSION+":"+keyframeId+(request.path("apply").asBoolean(false)?":apply":":shadow"));
        synchronized(LOCKS[Math.floorMod(assessmentKey.hashCode(),LOCKS.length)]){return reviewOnce(keyframeId,request,assessmentKey);}
    }
    private ObjectNode reviewOnce(String keyframeId,JsonNode request,String assessmentKey){
        ObjectNode frame=store.get(KEYFRAME,keyframeId);JsonNode generation=frame.path("generationInputSnapshot");
        ObjectNode existing=existingAssessment(project(frame),keyframeId,assessmentKey);if(existing!=null){ObjectNode response=frame.deepCopy();response.set("automaticReview",existing);return response;}
        boolean shadow=!request.path("apply").asBoolean(false);
        if(!generation.path("context").isObject()){ObjectNode job=store.get(GENERATION_JOB,required(frame,"generationJobId"));generation=job.path("inputSnapshot");}
        try{rules.requireDeterministicPass(generation.path("context"));}catch(DeterministicRuleViolationException failure){throw new WorkflowException("DETERMINISTIC_RULE_FAILED",failure.getMessage());}
        ObjectNode expected=expectedContexts.build(generation.path("context"));addReviewPolicy(expected,frame,generation);
        if(request.path("canaryAcceptance").asBoolean()||frame.path("simulated").asBoolean()&&request.path("deterministicAcceptance").asBoolean())expected.set("reviewPolicy",obj().put("importantCharacterFirstAppearance",false).put("importantLocationFirstAppearance",false));
        ObjectNode reviewInput=request.isObject()?(ObjectNode)request.deepCopy():obj();
        if(frame.path("simulated").asBoolean()&&request.path("deterministicAcceptance").asBoolean()&&!reviewInput.path("observedConstraints").isObject())reviewInput.set("observedConstraints",expected.path("requiredConstraints").deepCopy());
        ObjectNode generated=media.prepare(frame,generation,expected,reviewInput),budgetInput=reviewBudgetInput(generation);JsonNode result;
        boolean reserved=testBudget!=null&&testBudget.reserve("KEYFRAME_QC",budgetInput);
        try{result=protocol.validate(reviewer.review(expected,generated),expected);if(reserved)testBudget.settle("KEYFRAME_QC",budgetInput,false);}
        catch(ProviderException failure){if(reserved)testBudget.settle("KEYFRAME_QC",budgetInput,true);failedAssessment(frame,assessmentKey,shadow,failure);throw failure;}
        catch(RuntimeException failure){if(reserved)testBudget.settle("KEYFRAME_QC",budgetInput,true);throw failure;}
        VisualQualityPolicy.Decision route=policy.decide(result,expected,priorProviderDecisions(keyframeId));
        if(route==VisualQualityPolicy.Decision.AUTO_REGENERATE&&!request.path("allowAutomaticRepair").asBoolean(true))route=VisualQualityPolicy.Decision.MANUAL_REVIEW;
        ObjectNode body=reviewBody(expected,result,route).put("assessmentKey",assessmentKey);
        if(shadow||route==VisualQualityPolicy.Decision.MANUAL_REVIEW)return assessment(frame,body,shadow,route);
        ObjectNode reviewed=workflow.review(KEYFRAME,keyframeId,body);
        if(route==VisualQualityPolicy.Decision.AUTO_REGENERATE){
            String repairKey=request.path("requestKey").asText("vlm-repair-"+keyframeId+"-v"+frame.path("version").asInt());
            ObjectNode response=reviewed.deepCopy();response.set("automaticRepairJob",workflow.repairKeyframe(keyframeId,obj().put("requestKey",repairKey)));return response;
        }
        return reviewed;
    }

    private ObjectNode reviewBody(ObjectNode expected,JsonNode result,VisualQualityPolicy.Decision route){
        boolean passed=route==VisualQualityPolicy.Decision.AUTO_PASS;String decision=passed?"PASS":route==VisualQualityPolicy.Decision.AUTO_REGENERATE?"REGENERATE":"MANUAL_FIX";
        ObjectNode body=obj().put("passed",passed).put("decision",decision).put("reviewer","AUTOMATIC")
            .put("failureOrigin",result.path("failureOriginHint").asText(passed?"UNKNOWN":"PROVIDER_OUTPUT"))
            .put("failureReason",result.path("reason").asText()).put("notes",result.path("reason").asText())
            .put("score",result.path("overallScore").asDouble()).put("visualQuality",result.path("overallScore").asDouble());
        body.put("characterConsistency",score(result,"characterIdentity")).put("clothingConsistency",score(result,"clothingConsistency"))
            .put("locationConsistency",score(result,"locationConsistency")).put("propConsistency",score(result,"propConsistency"))
            .put("composition",score(result,"compositionQuality")).put("actionAccuracy",score(result,"actionAccuracy")).put("styleConsistency",score(result,"styleConsistency"));
        body.set("failureCodes",result.path("failureCodes").deepCopy());body.set("expectedContext",media.compactExpected(expected));body.set("qualityReviewResult",result.deepCopy());return body;
    }
    private ObjectNode assessment(ObjectNode frame,ObjectNode body,boolean shadow,VisualQualityPolicy.Decision route){
        ObjectNode qc=obj().put("projectId",project(frame)).put("targetKind",KEYFRAME.path()).put("targetId",id(frame)).put("shotId",required(frame,"shotId"))
            .put("reviewSequence",nextReviewSequence(frame)).put("passed",body.path("passed").asBoolean()).put("score",body.path("score").asDouble()).put("reviewer","AUTOMATIC")
            .put("decision",body.path("decision").asText()).put("routingDecision",route.name()).put("shadow",shadow).put("assessmentKey",text(body,"assessmentKey"))
            .put("manualReviewRequired",route==VisualQualityPolicy.Decision.MANUAL_REVIEW).put("notes",text(body,"notes"));
        for(String field:List.of("characterConsistency","clothingConsistency","locationConsistency","propConsistency","composition","actionAccuracy","styleConsistency","visualQuality"))qc.set(field,body.path(field).deepCopy());
        qc.set("failureCodes",body.path("failureCodes").deepCopy());qc.set("expectedContext",body.path("expectedContext").deepCopy());qc.set("qualityReviewResult",body.path("qualityReviewResult").deepCopy());
        String providerRequestId=text(body.path("qualityReviewResult").path("_provider"),"requestId");if(!providerRequestId.isBlank())qc.put("providerRequestId",providerRequestId);
        ObjectNode saved=store.create(QC_RESULT,QcGenerationProvenance.attach(qc,frame));ObjectNode response=frame.deepCopy();response.set("automaticReview",saved);return response;
    }
    private void addReviewPolicy(ObjectNode expected,ObjectNode frame,JsonNode generation){
        ObjectNode shot=store.get(SHOT,required(frame,"shotId"));boolean firstCharacter=false,firstLocation=true;
        Set<String> priorCharacters=new HashSet<>();String locationId=text(shot,"locationId");double storyTime=shot.path("storyTime").asDouble(Double.NaN);
        for(ObjectNode other:store.list(SHOT,project(frame),null))if(!id(other).equals(id(shot))&&precedes(other,shot,storyTime)){
            other.path("characterIds").forEach(value->priorCharacters.add(value.asText()));if(locationId.equals(text(other,"locationId")))firstLocation=false;
        }
        for(JsonNode character:shot.path("characterIds"))if(!priorCharacters.contains(character.asText())){firstCharacter=true;break;}
        expected.putObject("reviewPolicy").put("importantCharacterFirstAppearance",firstCharacter).put("importantLocationFirstAppearance",firstLocation);
    }
    private boolean precedes(JsonNode other,JsonNode shot,double storyTime){
        if(!Double.isNaN(storyTime)&&other.path("storyTime").isNumber()){
            int order=Double.compare(other.path("storyTime").asDouble(),storyTime);
            if(order!=0)return order<0;
        }
        return text(other,"sceneId").equals(text(shot,"sceneId"))&&other.path("shotNo").asInt(Integer.MAX_VALUE)<shot.path("shotNo").asInt(Integer.MAX_VALUE);
    }
    private List<String> priorProviderDecisions(String targetId){
        List<String> values=new ArrayList<>();for(ObjectNode qc:store.list(QC_RESULT,null,null))if(targetId.equals(text(qc,"targetId"))&&"AUTOMATIC".equals(text(qc,"reviewer"))){
            String decision=text(qc.path("qualityReviewResult"),"decision");if(!decision.isBlank())values.add(decision);
        }return values.isEmpty()?values:List.of(values.getLast());
    }
    private ObjectNode existingAssessment(String projectId,String targetId,String assessmentKey){for(ObjectNode qc:store.list(QC_RESULT,projectId,null))if(targetId.equals(text(qc,"targetId"))&&assessmentKey.equals(text(qc,"assessmentKey"))&&"AUTOMATIC".equals(text(qc,"reviewer")))return qc;return null;}
    private void failedAssessment(ObjectNode frame,String assessmentKey,boolean shadow,ProviderException failure){
        ObjectNode qc=obj().put("projectId",project(frame)).put("targetKind",KEYFRAME.path()).put("targetId",id(frame)).put("shotId",required(frame,"shotId"))
            .put("reviewSequence",nextReviewSequence(frame)).put("reviewer","AUTOMATIC").put("assessmentKey",assessmentKey).put("assessmentStatus","FAILED").put("shadow",shadow).put("passed",false)
            .put("decision","MANUAL_FIX").put("routingDecision","MANUAL_REVIEW").put("failureCode",failure.code()).put("failureReason",failure.getMessage()==null?"视觉模型请求失败":failure.getMessage())
            .put("retryable",failure.retryable()).put("submissionUncertain",failure.uncertain());if(failure.requestId()!=null&&!failure.requestId().isBlank())qc.put("providerRequestId",failure.requestId());if(failure.rawOutput()!=null)qc.put("providerOutputRaw",bounded(failure.rawOutput()));store.create(QC_RESULT,QcGenerationProvenance.attach(qc,frame));
    }
    private String bounded(String value){return value.length()<=16000?value:value.substring(0,16000);}
    private ObjectNode reviewBudgetInput(JsonNode generation){ObjectNode input=obj();for(String field:List.of("testRun","testRunId","testPhase"))if(generation.has(field))input.set(field,generation.path(field).deepCopy());return input;}
    private long nextReviewSequence(ObjectNode frame){return store.list(QC_RESULT,project(frame),null).stream().filter(review->id(frame).equals(text(review,"targetId"))).mapToLong(review->review.path("reviewSequence").asLong(0)).max().orElse(0)+1;}
    private double score(JsonNode result,String metric){return result.path(metric).path("score").asDouble();}
}
