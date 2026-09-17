package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Central confidence policy that prevents a VLM verdict from directly opening the production gate. */
@Component
public class VisualQualityPolicy {
    public enum Decision { AUTO_PASS,AUTO_REGENERATE,MANUAL_REVIEW }
    private static final Set<String> HIGH_RISK=Set.of("characterIdentity","characterCount","propConsistency","locationConsistency","spatialConsistency","continuityWithPreviousShot");
    private final double highRiskConfidence,standardConfidence,regenerateConfidence;

    public VisualQualityPolicy(
        @Value("${drama.quality.high-risk-confidence:0.90}") double highRiskConfidence,
        @Value("${drama.quality.standard-confidence:0.80}") double standardConfidence,
        @Value("${drama.quality.regenerate-confidence:0.85}") double regenerateConfidence){
        this.highRiskConfidence=threshold(highRiskConfidence);this.standardConfidence=threshold(standardConfidence);this.regenerateConfidence=threshold(regenerateConfidence);
    }

    public Decision decide(JsonNode result,JsonNode expected,List<String> priorDecisions){
        if(expected.path("reviewPolicy").path("importantCharacterFirstAppearance").asBoolean()
            ||expected.path("reviewPolicy").path("importantLocationFirstAppearance").asBoolean())return Decision.MANUAL_REVIEW;
        String providerDecision=result.path("decision").asText();
        if("MANUAL_REVIEW".equals(providerDecision))return Decision.MANUAL_REVIEW;
        if(priorDecisions!=null&&!priorDecisions.isEmpty()&&!providerDecision.equals(priorDecisions.getLast()))return Decision.MANUAL_REVIEW;
        boolean failed=false;
        for(String metric:VisualQualityProtocol.METRICS){
            JsonNode verdict=result.path(metric);boolean pass=verdict.path("pass").asBoolean();double confidence=verdict.path("confidence").asDouble();
            if(pass){if(confidence<(HIGH_RISK.contains(metric)?highRiskConfidence:standardConfidence))return Decision.MANUAL_REVIEW;}
            else{failed=true;if(confidence<Math.max(regenerateConfidence,HIGH_RISK.contains(metric)?highRiskConfidence:0))return Decision.MANUAL_REVIEW;}
        }
        if(result.path("overallConfidence").asDouble()<standardConfidence)return Decision.MANUAL_REVIEW;
        if(failed&&!"REGENERATE".equals(providerDecision))return Decision.MANUAL_REVIEW;
        if(!failed&&!"PASS".equals(providerDecision))return Decision.MANUAL_REVIEW;
        return failed?Decision.AUTO_REGENERATE:Decision.AUTO_PASS;
    }
    private double threshold(double value){if(value<0||value>1)throw new IllegalArgumentException("视觉质量置信阈值必须在 0 到 1 之间");return value;}
}
