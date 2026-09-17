package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.VisualCalibrationMetrics;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.QC_RESULT;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class VisualCalibrationService {
    private final DocumentStore store;private final VisualCalibrationMetrics calculator=new VisualCalibrationMetrics();
    public VisualCalibrationService(DocumentStore store){this.store=store;}
    public ObjectNode metrics(String projectId){
        Map<String,ObjectNode> human=new LinkedHashMap<>(),automatic=new LinkedHashMap<>();
        for(ObjectNode review:store.list(QC_RESULT,projectId,null)){
            String key=text(review,"targetKind")+":"+text(review,"targetId");
            if("HUMAN".equals(text(review,"reviewer"))&&!review.path("shadow").asBoolean())human.merge(key,review,this::latest);
            if("AUTOMATIC".equals(text(review,"reviewer"))&&review.path("shadow").asBoolean()&&review.path("qualityReviewResult").isObject())automatic.merge(key,review,this::latest);
        }
        List<VisualCalibrationMetrics.Sample> samples=new ArrayList<>();ArrayNode targets=JsonNodeFactory.instance.arrayNode();
        for(var entry:human.entrySet()){
            ObjectNode predicted=automatic.get(entry.getKey());if(predicted==null)continue;ObjectNode truth=entry.getValue();
            boolean humanPassed=truth.path("passed").asBoolean();boolean humanCodesLabeled=humanPassed||"EXPLICIT".equals(text(truth.path("diagnosis"),"failureCodesSource"))||truth.path("failureCodes").isArray();
            Set<String> humanCodes=humanCodesLabeled?codes(truth.path("diagnosis").path("failureCodes")):Set.of();if(humanCodes.isEmpty()&&humanCodesLabeled)humanCodes=codes(truth.path("failureCodes"));
            Set<String> predictedCodes=codes(predicted.path("qualityReviewResult").path("failureCodes"));if(predictedCodes.isEmpty())predictedCodes=codes(predicted.path("failureCodes"));
            samples.add(new VisualCalibrationMetrics.Sample(humanPassed,text(predicted,"routingDecision"),humanCodes,predictedCodes));
            ObjectNode target=targets.addObject().put("targetKind",text(truth,"targetKind")).put("targetId",text(truth,"targetId")).put("humanPassed",humanPassed).put("humanFailureCodesLabeled",humanCodesLabeled).put("routingDecision",text(predicted,"routingDecision")).put("humanReviewId",id(truth)).put("automaticReviewId",id(predicted));
            if(!text(predicted,"providerRequestId").isBlank())target.put("providerRequestId",text(predicted,"providerRequestId"));
        }
        ObjectNode result=calculator.calculate(samples);result.set("targets",targets);result.put("unpairedHumanReviews",Math.max(0,human.size()-samples.size())).put("unpairedAutomaticReviews",Math.max(0,automatic.size()-samples.size()));return result;
    }
    private ObjectNode latest(ObjectNode current,ObjectNode next){return laterReview(current,next);}
    private Set<String> codes(JsonNode values){Set<String> result=new LinkedHashSet<>();values.forEach(value->result.add(value.asText()));return result;}
}
