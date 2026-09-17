package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VisualQualityPolicyTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final VisualQualityPolicy policy=new VisualQualityPolicy(.90,.80,.85);

    @Test void routesOnlyHighConfidenceCleanResultsToAutoPass(){
        assertThat(policy.decide(result(true,.96),expected(),List.of())).isEqualTo(VisualQualityPolicy.Decision.AUTO_PASS);
        ObjectNode uncertain=result(true,.96);uncertain.withObject("characterIdentity").put("confidence",.82);
        assertThat(policy.decide(uncertain,expected(),List.of())).isEqualTo(VisualQualityPolicy.Decision.MANUAL_REVIEW);
    }
    @Test void routesHighConfidenceFailureToRepairAndUncertainFailureToHuman(){
        ObjectNode certain=result(true,.96);fail(certain,"characterIdentity",.96,"IDENTITY_MISMATCH");
        assertThat(policy.decide(certain,expected(),List.of())).isEqualTo(VisualQualityPolicy.Decision.AUTO_REGENERATE);
        ObjectNode uncertain=result(true,.96);fail(uncertain,"characterIdentity",.70,"IDENTITY_MISMATCH");
        assertThat(policy.decide(uncertain,expected(),List.of())).isEqualTo(VisualQualityPolicy.Decision.MANUAL_REVIEW);
    }
    @Test void firstAppearanceAndChangedVerdictRequireHumanReview(){
        ObjectNode first=expected();first.putObject("reviewPolicy").put("importantCharacterFirstAppearance",true);
        assertThat(policy.decide(result(true,.96),first,List.of())).isEqualTo(VisualQualityPolicy.Decision.MANUAL_REVIEW);
        assertThat(policy.decide(result(true,.96),expected(),List.of("REGENERATE"))).isEqualTo(VisualQualityPolicy.Decision.MANUAL_REVIEW);
    }

    private ObjectNode expected(){return mapper.createObjectNode();}
    private ObjectNode result(boolean pass,double confidence){ObjectNode r=mapper.createObjectNode();for(String name:VisualQualityProtocol.METRICS)r.set(name,metric(pass,pass?95:40,confidence));r.put("overallScore",pass?95:40).put("overallConfidence",confidence).put("decision",pass?"PASS":"REGENERATE");r.putArray("failureCodes");return r;}
    private ObjectNode metric(boolean pass,double score,double confidence){return mapper.createObjectNode().put("pass",pass).put("score",score).put("confidence",confidence).put("reason",pass?"符合":"不符合");}
    private void fail(ObjectNode result,String metric,double confidence,String code){result.set(metric,metric(false,35,confidence));result.put("decision","REGENERATE").put("overallScore",60).put("overallConfidence",confidence);result.withArray("failureCodes").add(code);}
}
