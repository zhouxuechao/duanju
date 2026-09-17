package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Provider-neutral comparison of shadow VLM decisions with human ground truth. */
public final class VisualCalibrationMetrics {
    public record Sample(boolean humanPass,String routingDecision,Set<String> humanFailureCodes,Set<String> predictedFailureCodes){
        public Sample{humanFailureCodes=Set.copyOf(humanFailureCodes);predictedFailureCodes=Set.copyOf(predictedFailureCodes);}
    }
    private static final Map<String,Set<String>> CATEGORIES=new LinkedHashMap<>();
    static{
        CATEGORIES.put("identity",Set.of("IDENTITY_MISMATCH"));CATEGORIES.put("characterCount",Set.of("CHARACTER_COUNT_ERROR"));CATEGORIES.put("age",Set.of("AGE_MISMATCH"));CATEGORIES.put("hair",Set.of("HAIR_MISMATCH"));CATEGORIES.put("clothing",Set.of("CLOTHING_MISMATCH"));CATEGORIES.put("prop",Set.of("PROP_MISMATCH"));CATEGORIES.put("location",Set.of("LOCATION_MISMATCH"));CATEGORIES.put("spatial",Set.of("POSITION_MISMATCH"));CATEGORIES.put("action",Set.of("ACTION_MISMATCH"));CATEGORIES.put("expression",Set.of("EXPRESSION_MISMATCH"));CATEGORIES.put("style",Set.of("STYLE_MISMATCH"));CATEGORIES.put("camera",Set.of("CAMERA_MISMATCH"));CATEGORIES.put("composition",Set.of("COMPOSITION_ERROR"));CATEGORIES.put("textOrWatermark",Set.of("TEXT_OR_WATERMARK"));CATEGORIES.put("continuity",Set.of("CONTINUITY_MISMATCH"));CATEGORIES.put("reference",Set.of("REFERENCE_FAILURE"));
    }
    public ObjectNode calculate(List<Sample> samples){
        int total=samples.size(),autoPass=0,autoRegenerate=0,manual=0,tp=0,fp=0,falseReject=0,humanPass=0,humanFail=0,agreement=0,exactFailures=0,failureCodeGroundTruth=0,categoryGroundTruth=0;
        Map<String,Integer> categoryCorrect=new LinkedHashMap<>(),categoryPositive=new LinkedHashMap<>();CATEGORIES.keySet().forEach(name->{categoryCorrect.put(name,0);categoryPositive.put(name,0);});
        for(Sample sample:samples){
            boolean predictedPass="AUTO_PASS".equals(sample.routingDecision()),predictedFail="AUTO_REGENERATE".equals(sample.routingDecision());
            if(predictedPass)autoPass++;else if(predictedFail)autoRegenerate++;else manual++;
            if(sample.humanPass()){humanPass++;if(predictedPass){tp++;agreement++;}if(predictedFail)falseReject++;}
            else{humanFail++;if(predictedPass)fp++;if(predictedFail)agreement++;if(!sample.humanFailureCodes().isEmpty()){failureCodeGroundTruth++;if(sample.humanFailureCodes().equals(sample.predictedFailureCodes()))exactFailures++;}}
            boolean categoryLabeled=sample.humanPass()||!sample.humanFailureCodes().isEmpty();
            if(categoryLabeled){categoryGroundTruth++;for(var entry:CATEGORIES.entrySet()){boolean humanHas=has(sample.humanFailureCodes(),entry.getValue());if(humanHas)categoryPositive.compute(entry.getKey(),(key,value)->value+1);if(humanHas==has(sample.predictedFailureCodes(),entry.getValue()))categoryCorrect.compute(entry.getKey(),(key,value)->value+1);}}
        }
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("groundTruthSamples",total).put("agreementRate",rate(agreement,total))
            .put("passPrecision",rate(tp,tp+fp)).put("passRecall",rate(tp,humanPass)).put("falsePassRate",rate(fp,humanFail)).put("falseRejectRate",rate(falseReject,humanPass))
            .put("autoPassRate",rate(autoPass,total)).put("autoRegenerateRate",rate(autoRegenerate,total)).put("manualReviewRate",rate(manual,total))
            .put("failureCodeGroundTruthSamples",failureCodeGroundTruth).put("categoryGroundTruthSamples",categoryGroundTruth).put("failureCodeAccuracy",rate(exactFailures,failureCodeGroundTruth));
        int categoryDenominator=categoryGroundTruth;ObjectNode categories=result.putObject("categoryAccuracy");categoryCorrect.forEach((name,value)->categories.put(name,rate(value,categoryDenominator)));ObjectNode positives=result.putObject("categoryPositiveGroundTruthSamples");categoryPositive.forEach(positives::put);return result;
    }
    private boolean has(Set<String> actual,Set<String> category){return !Collections.disjoint(actual,category);}
    private double rate(int value,int total){return total==0?0:(double)value/total;}
}
