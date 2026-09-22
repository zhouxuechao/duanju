package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VisualQualityProtocolTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void rejectsFreeTextAndIncompleteResults(){
        var protocol=new VisualQualityProtocol();
        assertThatThrownBy(()->protocol.validate(mapper.createObjectNode().put("text","looks fine"),expected()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("结构化");
        ObjectNode incomplete=valid();incomplete.remove("cameraAccuracy");
        assertThatThrownBy(()->protocol.validate(incomplete,expected()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cameraAccuracy");
    }

    @Test void requiresAnIdentityVerdictForEveryExpectedCharacter(){
        ObjectNode result=valid();result.withArray("characters").removeAll();
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
    }

    @Test void acceptsTheCompleteStrictContract(){
        assertThatCode(()->new VisualQualityProtocol().validate(valid(),expected())).doesNotThrowAnyException();
    }
    @Test void everyQualityDimensionRequiresObservableEvidence(){
        var properties=(java.util.Map<?,?>)new VisualQualityProtocol().schema().get("properties");
        var metric=(java.util.Map<?,?>)properties.get("propConsistency");
        assertThat(((java.util.List<?>)metric.get("required")).stream().map(Object::toString)).contains("evidence");
        ObjectNode result=valid();result.withObject("propConsistency").put("evidence","");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected())).hasMessageContaining("propConsistency.evidence");
    }
    @Test void requiresAttributionForDirectorComplianceFailures(){
        ObjectNode result=valid();result.remove("failureOriginHint");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected())).hasMessageContaining("failureOriginHint");
    }
    @Test void attributionSchemaUsesTheCurrentStagedDirectorDefinitions(){
        var properties=(java.util.Map<?,?>)new VisualQualityProtocol().schema().get("properties");
        var definition=(java.util.Map<?,?>)properties.get("failureOriginHint");
        var origins=((java.util.List<?>)definition.get("enum")).stream().map(Object::toString).toList();
        assertThat(origins).contains("DIRECTOR_PLAN","SHOT_DETAIL").doesNotContain("SHOT_PLAN");
        ObjectNode legacy=valid().put("failureOriginHint","SHOT_PLAN");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(legacy,expected())).hasMessageContaining("failureOriginHint");
    }

    @Test void rejectsAFailedMetricWithoutItsFailureCode(){
        ObjectNode result=valid();
        result.set("spatialConsistency",metric(false,35,.96,"木门被打开且人物越过门槛"));
        result.put("decision","REGENERATE");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spatialConsistency")
                .hasMessageContaining("POSITION_MISMATCH");
    }

    @Test void rejectsAFailureCodeWhoseMetricStillPasses(){
        ObjectNode result=valid();result.withArray("failureCodes").add("COMPOSITION_ERROR");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPOSITION_ERROR")
                .hasMessageContaining("compositionQuality");
    }

    @Test void distinguishesCameraExecutionFromCompositionFailure(){
        ObjectNode result=valid();
        result.set("cameraAccuracy",metric(false,25,.96,"拍摄方向与机位计划相反"));
        result.withArray("failureCodes").add("CAMERA_MISMATCH");
        result.put("decision","REGENERATE");
        assertThatCode(()->new VisualQualityProtocol().validate(result,expected())).doesNotThrowAnyException();
    }

    @Test void requiresAnExplicitCodeForPreviousShotContinuityFailure(){
        ObjectNode result=valid();
        result.set("continuityWithPreviousShot",metric(false,30,.97,"人物持物与上一镜不连续"));
        result.put("decision","REGENERATE");
        assertThatThrownBy(()->new VisualQualityProtocol().validate(result,expected()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONTINUITY_MISMATCH");
    }

    @Test void priorHumanFailureIsAnExplicitMandatoryRegressionCheck(){
        ObjectNode expected=expected();
        expected.withObject("requiredConstraints").put("priorFailureToRecheck","上一版把北墙牌位复制到了南门上");
        String instructions=new VisualQualityProtocol().reviewInstructions(expected,false);
        assertThat(instructions).contains("上一版把北墙牌位复制到了南门上")
            .contains("必须先复查 priorFailureToRecheck")
            .contains("不得沿用上一轮的通过结论");
    }

    @Test void shallowDepthOfFieldNeverWaivesFixedBackgroundTopology(){
        String instructions=new VisualQualityProtocol().reviewInstructions(expected(),false);
        assertThat(instructions)
            .contains("浅景深或背景虚化不得免除固定空间拓扑检查")
            .contains("所属承载面")
            .contains("对立承载面的设施被画到同一承载面");
    }

    @Test void detailShotsPreserveVisibleTopologyWithoutInventingAnAllLandmarksRequirement(){
        ObjectNode expected=expected();
        expected.withObject("requiredConstraints").withObject("composition").withObject("surfaceTopology")
            .put("visibilityMode","PRESERVE_IF_VISIBLE");
        String instructions=new VisualQualityProtocol().reviewInstructions(expected,false);
        assertThat(instructions)
            .contains("PRESERVE_IF_VISIBLE")
            .contains("不要求视锥外或被特写裁掉的非主体设施强行入画")
            .contains("只要设施进入画面，就必须保持承载面和拓扑关系");
    }

    private ObjectNode expected(){
        ObjectNode expected=mapper.createObjectNode();
        expected.putObject("requiredConstraints").putArray("characterIdentity").addObject().put("id","actor").put("name","主角");
        return expected;
    }
    private ObjectNode valid(){
        ObjectNode result=mapper.createObjectNode();
        for(String metric:VisualQualityProtocol.METRICS)result.set(metric,metric(true,96,.96,"符合"));
        result.putArray("characters").addObject().put("characterId","actor").put("characterName","主角").set("identity",metric(true,97,.98,"同一角色"));
        result.putArray("failureCodes");result.put("overallScore",96).put("overallConfidence",.96).put("decision","PASS").put("failureOriginHint","UNKNOWN").put("reason","全部约束符合");
        return result;
    }
    private ObjectNode metric(boolean pass,double score,double confidence,String reason){return mapper.createObjectNode().put("score",score).put("pass",pass).put("confidence",confidence).put("reason",reason).put("evidence",reason);}
}
