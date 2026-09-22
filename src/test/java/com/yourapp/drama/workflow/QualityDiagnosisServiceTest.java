package com.yourapp.drama.workflow;import org.junit.jupiter.api.Test;import static com.yourapp.drama.workflow.Documents.*;import static org.assertj.core.api.Assertions.*;
class QualityDiagnosisServiceTest{
 @Test void classifiesDoorPropAndCompositionFailures(){var d=new QualityDiagnosisService().diagnose(obj().put("notes","门被打开，人物全身入画，右手没有握住铜铃木柄"));assertThat(d.path("failureCodes").toString()).contains("LOCATION_MISMATCH","PROP_MISMATCH","COMPOSITION_ERROR");assertThat(d.path("recommendedRepair").asText()).isEqualTo("REPLAN_SHOT");assertThat(d.path("failureCodesSource").asText()).isEqualTo("INFERRED");}
 @Test void coversEverySupportedVisualFailureCategory(){var d=new QualityDiagnosisService().diagnose(obj().put("notes","年龄不对，发型改变，动作和表情错误，风格跑偏，画面有水印，参考图没有生效"));assertThat(d.path("failureCodes").toString()).contains("AGE_MISMATCH","HAIR_MISMATCH","ACTION_MISMATCH","EXPRESSION_MISMATCH","STYLE_MISMATCH","TEXT_OR_WATERMARK","REFERENCE_FAILURE");}
 @Test void stateOriginsRouteToTheMatchingStateRepair(){
  var service=new QualityDiagnosisService();
  assertThat(service.diagnose(obj().put("notes","地点错误").put("failureOrigin","STORY_STATE")).path("recommendedRepair").asText()).isEqualTo("UPDATE_LOCATION_STATE");
  assertThat(service.diagnose(obj().put("notes","道具错误").put("failureOrigin","STORY_STATE")).path("recommendedRepair").asText()).isEqualTo("UPDATE_PROP_STATE");
  assertThat(service.diagnose(obj().put("notes","服装错误").put("failureOrigin","STORY_STATE")).path("recommendedRepair").asText()).isEqualTo("UPDATE_CHARACTER_STATE");
 }
 @Test void explicitFailureCodesAreNotExpandedByPositiveLanguage(){
  var review=obj().put("notes","僵尸身份可辨，服装正确，只有木门被打开");review.putArray("failureCodes").add("LOCATION_MISMATCH");
  var diagnosis=new QualityDiagnosisService().diagnose(review);
  assertThat(diagnosis.path("failureCodes")).extracting(n->n.asText()).containsExactly("LOCATION_MISMATCH");
  assertThat(diagnosis.path("failureCodesSource").asText()).isEqualTo("EXPLICIT");
 }
 @Test void genreVocabularyAloneDoesNotCreateAnIdentityFailure(){
  var diagnosis=new QualityDiagnosisService().diagnose(obj().put("notes","僵尸站在门外，门上出现了不属于场景参考图的装饰"));
  assertThat(diagnosis.path("failureCodes")).extracting(n->n.asText())
    .contains("LOCATION_MISMATCH")
    .doesNotContain("IDENTITY_MISMATCH");
 }
 @Test void classifiesShotToShotContinuityFailure(){
  var diagnosis=new QualityDiagnosisService().diagnose(obj().put("notes","人物持物与上一镜不连续，动作发生跳变"));
  assertThat(diagnosis.path("failureCodes")).extracting(n->n.asText()).contains("CONTINUITY_MISMATCH");
 }
 @Test void separatesCameraDirectionFromComposition(){
  var diagnosis=new QualityDiagnosisService().diagnose(obj().put("notes","机位拍摄方向相反，但景别和主体构图正确"));
  assertThat(diagnosis.path("failureCodes")).extracting(n->n.asText()).contains("CAMERA_MISMATCH");
 }
 @Test void stagedDirectorOriginsRouteToLocalReplanning(){
  var service=new QualityDiagnosisService();
  assertThat(service.diagnose(obj().put("notes","导演节拍不正确").put("failureOrigin","DIRECTOR_PLAN")).path("recommendedRepair").asText()).isEqualTo("REPLAN_SCENE");
  assertThat(service.diagnose(obj().put("notes","单镜机位不正确").put("failureOrigin","SHOT_DETAIL")).path("recommendedRepair").asText()).isEqualTo("REPLAN_SHOT");
 }
 @Test void buildsDimensionScopedRepairPlanThatPreservesCorrectVisualWork(){
  var review=obj().put("notes","动作方向与剧本相反").put("failureOrigin","PROVIDER_OUTPUT");review.putArray("failureCodes").add("ACTION_MISMATCH");
  var plan=new QualityDiagnosisService().diagnose(review).path("repairPlan");
  assertThat(plan.path("repairDimensions")).extracting(n->n.asText()).containsExactly("ACTION");
  assertThat(plan.path("preserveDimensions")).extracting(n->n.asText()).contains("IDENTITY","COSTUME","PROP","BACKGROUND","CAMERA","LIGHTING","STYLE");
  assertThat(plan.path("changedPromptSections")).extracting(n->n.asText()).containsExactly("TIMED BEATS");
  assertThat(plan.path("scope").asText()).isEqualTo("TAKE");
 }
}
