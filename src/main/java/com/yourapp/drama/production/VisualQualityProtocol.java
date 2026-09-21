package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Strict provider-neutral contract for image and video visual reviews. */
public final class VisualQualityProtocol {
    public static final String VERSION="13";
    public static final List<String> FAILURE_ORIGINS=List.of("DIRECTOR_PLAN","SHOT_DETAIL","PROMPT_BUILD","PROVIDER_OUTPUT","CONTEXT_RESOLUTION","REFERENCE_SELECTION","UNKNOWN");
    public static final List<String> METRICS=List.of(
        "characterIdentity","characterCount","ageConsistency","hairConsistency","clothingConsistency",
        "propConsistency","locationConsistency","spatialConsistency","actionAccuracy","expressionAccuracy",
        "cameraAccuracy","compositionQuality","styleConsistency","textOrWatermark","continuityWithPreviousShot");
    public static final List<String> FAILURE_CODES=List.of(
        "IDENTITY_MISMATCH","CHARACTER_COUNT_ERROR","AGE_MISMATCH","HAIR_MISMATCH","CLOTHING_MISMATCH",
        "PROP_MISMATCH","LOCATION_MISMATCH","POSITION_MISMATCH","ACTION_MISMATCH","EXPRESSION_MISMATCH",
        "STYLE_MISMATCH","CAMERA_MISMATCH","COMPOSITION_ERROR","TEXT_OR_WATERMARK","CONTINUITY_MISMATCH","REFERENCE_FAILURE","OTHER");
    private static final Map<String,String> METRIC_FAILURE_CODE=Map.ofEntries(
        Map.entry("characterIdentity","IDENTITY_MISMATCH"),Map.entry("characterCount","CHARACTER_COUNT_ERROR"),
        Map.entry("ageConsistency","AGE_MISMATCH"),Map.entry("hairConsistency","HAIR_MISMATCH"),
        Map.entry("clothingConsistency","CLOTHING_MISMATCH"),Map.entry("propConsistency","PROP_MISMATCH"),
        Map.entry("locationConsistency","LOCATION_MISMATCH"),Map.entry("spatialConsistency","POSITION_MISMATCH"),
        Map.entry("actionAccuracy","ACTION_MISMATCH"),Map.entry("expressionAccuracy","EXPRESSION_MISMATCH"),
        Map.entry("cameraAccuracy","CAMERA_MISMATCH"),Map.entry("compositionQuality","COMPOSITION_ERROR"),
        Map.entry("styleConsistency","STYLE_MISMATCH"),Map.entry("textOrWatermark","TEXT_OR_WATERMARK"),
        Map.entry("continuityWithPreviousShot","CONTINUITY_MISMATCH"));

    public String reviewInstructions(JsonNode expected,boolean video){
        String subject=video
                ?"五张 VIDEO_FRAME 按时间顺序采样自同一条待审核视频；已批准参考图只用于比较身份、定装、地点与道具。"
                :"第一张 GENERATED_IMAGE 是待审核生成图；其余带标签图片都是已批准参考。";
        return """
            你是短剧视觉连续性质检员。%s
            按证据顺序审查，不要先给总体印象：
            1. 逐条引用硬约束中的可观察事实，再与待审核画面逐项比较；看不见的内容不得猜测。
            若 EXPECTED_CONTEXT 中存在 priorFailureToRecheck，必须先复查 priorFailureToRecheck 并在对应 metric.reason 中说明画面证据；不得沿用上一轮的通过结论，也不得只依据文字设定宣称问题已消失。
            2. 逐个角色比较身份、人数、年龄、发型和服装；每个预期角色都要单独返回身份判断。characterIdentity 和 characters[].identity 只评价永久身份外观（脸部核心特征、基础体态和可识别身份），站位、视线、动作或表情错误不得导致 identity 失败，必须分别记入 spatialConsistency、actionAccuracy 或 expressionAccuracy。
            3. 逐项对照地点参考图，核对固定布局、状态和人物是否越界。必须先使用 worldToScreenProjection 将世界方位投影为画面左右和远近，再使用 composition.surfaceTopology 核对每个固定设施的所属承载面，以及设施之间的同面、对立、相邻、前后和内外关系；不得把世界方位直接猜成画面方位。严格执行 surfaceTopology.visibilityMode：PRESERVE_IF_VISIBLE 不要求视锥外或被特写裁掉的非主体设施强行入画，只要设施进入画面，就必须保持承载面和拓扑关系；REQUIRE_FRAMED_LANDMARKS 才要求导演方案和可见特征指定的空间地标入画。不得仅因非主体设施没有出现在特写中判定地点失败。浅景深或背景虚化不得免除固定空间拓扑检查：可辨认的固定设施即使失焦，仍须核对其身份、数量、所属承载面和关系；分属对立承载面的设施被画到同一承载面，或固定设施被复制、移位、合并、增删时，locationConsistency 必须失败并报告 LOCATION_MISMATCH。仅当本镜要求展示空间地标而虚化使拓扑无法可靠判断时，才降低 confidence 并选择 MANUAL_REVIEW；再核对景别、拍摄方向、主体位置、相机高度与俯仰角。
            4. 核对道具持有人、握持方式、尺寸和状态。每件道具必须先拆分为整体轮廓、主体、连接或握持部件、附件、材质纹理和尺寸比例，再逐项对照道具文字设定与 PROP 参考图；不能因为用途相同或大致像同类物品就判定通过。任一可见结构部件与道具参考不符时，propConsistency 必须失败并报告 PROP_MISMATCH。若 composition.interactionGeometry 存在 holderContacts，必须同时核对指定左/右身体部位、身体世界站位投影、肢体入画来源、身体朝向和实际接触点；左右镜像、手臂从与身体投影相反方向进入、断肢、持物者站位漂移时 spatialConsistency 必须失败并报告 POSITION_MISMATCH，握错部件或接触点错误时 propConsistency 必须失败并报告 PROP_MISMATCH。若存在 previousAcceptedEnd，必须把上一条已采用视频的实际末态与当前视频首帧及 shotStart 比较人物身份、服装、姿态、运动相位、银幕方向、道具持有人、地点和灯光；任何无授权跳变都令 continuityWithPreviousShot 失败并报告 CONTINUITY_MISMATCH。再核对动作和表情。
            5. 水印必须最后检查，且不得掩盖其他失败项。一个画面存在多个偏差时必须全部报告。
            failureOriginHint 只做证据归因：节拍、镜头顺序或镜头骨架本身矛盾选 DIRECTOR_PLAN；单镜机位、构图、调度、表演或参考视角本身矛盾选 SHOT_DETAIL；发送给模型的视觉要求缺项选 PROMPT_BUILD；方案和请求正确但画面未执行选 PROVIDER_OUTPUT；无法判断选 UNKNOWN。
            每个独立偏差都必须同时反映在对应 metric 和 failureCodes 中。硬约束不符合时，对应 metric 的 pass 必须为 false；无法可靠判断时降低 confidence 并选择 MANUAL_REVIEW。只返回 JSON Schema 规定的对象。
            EXPECTED_CONTEXT=%s""".formatted(subject,expected.path("requiredConstraints"));
    }

    public JsonNode validate(JsonNode result,JsonNode expected){
        if(!result.isObject())throw invalid("VLM 必须返回结构化对象");
        for(String name:METRICS)metric(result.path(name),name);
        if(!result.path("characters").isArray())throw invalid("characters 必须逐角色返回");
        Set<String> actual=new HashSet<>();
        for(JsonNode character:result.path("characters")){
            String id=character.path("characterId").asText();if(id.isBlank()||!actual.add(id))throw invalid("characters.characterId 缺失或重复");
            metric(character.path("identity"),"characters["+id+"].identity");
        }
        for(JsonNode character:expected.path("requiredConstraints").path("characterIdentity")){
            String id=character.path("id").asText();if(!id.isBlank()&&!actual.contains(id))throw invalid("缺少角色 "+id+" 的身份判断");
        }
        if(!result.path("failureCodes").isArray())throw invalid("failureCodes 必须是数组");
        Set<String> failureCodes=new LinkedHashSet<>();
        for(JsonNode code:result.path("failureCodes")){if(!FAILURE_CODES.contains(code.asText()))throw invalid("failureCodes 包含未知值："+code.asText());failureCodes.add(code.asText());}
        for(var entry:METRIC_FAILURE_CODE.entrySet())if(!result.path(entry.getKey()).path("pass").asBoolean()&&!failureCodes.contains(entry.getValue()))throw invalid(entry.getKey()+" 未通过时 failureCodes 必须包含 "+entry.getValue());
        for(String code:failureCodes)if(!failureCodeHasFailedMetric(result,code))throw invalid(code+" 存在但对应 metric（"+metricsFor(code)+"）仍全部通过");
        range(result.path("overallScore"),0,100,"overallScore");range(result.path("overallConfidence"),0,1,"overallConfidence");
        if(!Set.of("PASS","REGENERATE","MANUAL_REVIEW").contains(result.path("decision").asText()))throw invalid("decision 无效");
        if(!FAILURE_ORIGINS.contains(result.path("failureOriginHint").asText()))throw invalid("failureOriginHint 无效或缺失");
        if(!result.path("reason").isTextual()||result.path("reason").asText().isBlank())throw invalid("reason 缺失");
        return result;
    }

    public Map<String,Object> schema(){return schema(null);}
    public Map<String,Object> schema(JsonNode expected){
        Map<String,Object> properties=new LinkedHashMap<>();for(String metric:METRICS)properties.put(metric,metricSchema());
        List<String> characterIds=new ArrayList<>();if(expected!=null)for(JsonNode character:expected.path("requiredConstraints").path("characterIdentity")){String id=character.path("id").asText();if(!id.isBlank())characterIds.add(id);}
        Map<String,Object> characterId=characterIds.isEmpty()?Map.of("type","string","minLength",1):Map.of("type","string","enum",List.copyOf(characterIds));
        Map<String,Object> characters=new LinkedHashMap<>();characters.put("type","array");characters.put("items",Map.of("type","object","properties",Map.of(
            "characterId",characterId,"characterName",Map.of("type","string"),"identity",metricSchema()),
            "required",List.of("characterId","characterName","identity"),"additionalProperties",false));if(!characterIds.isEmpty()){characters.put("minItems",characterIds.size());characters.put("maxItems",characterIds.size());}properties.put("characters",characters);
        properties.put("failureCodes",Map.of("type","array","items",Map.of("type","string","enum",FAILURE_CODES)));
        properties.put("overallScore",Map.of("type","number","minimum",0,"maximum",100));
        properties.put("overallConfidence",Map.of("type","number","minimum",0,"maximum",1));
        properties.put("decision",Map.of("type","string","enum",List.of("PASS","REGENERATE","MANUAL_REVIEW")));
        properties.put("failureOriginHint",Map.of("type","string","enum",FAILURE_ORIGINS));
        properties.put("reason",Map.of("type","string","minLength",1));
        List<String> required=new ArrayList<>(METRICS);required.addAll(List.of("characters","failureCodes","overallScore","overallConfidence","decision","failureOriginHint","reason"));
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }

    private Map<String,Object> metricSchema(){return Map.of("type","object","properties",Map.of(
        "score",Map.of("type","number","minimum",0,"maximum",100),"pass",Map.of("type","boolean"),
        "confidence",Map.of("type","number","minimum",0,"maximum",1),"reason",Map.of("type","string","minLength",1)),
        "required",List.of("score","pass","confidence","reason"),"additionalProperties",false);}
    private void metric(JsonNode value,String name){if(!value.isObject())throw invalid(name+" 缺失");range(value.path("score"),0,100,name+".score");if(!value.path("pass").isBoolean())throw invalid(name+".pass 缺失");range(value.path("confidence"),0,1,name+".confidence");if(!value.path("reason").isTextual()||value.path("reason").asText().isBlank())throw invalid(name+".reason 缺失");}
    private boolean failureCodeHasFailedMetric(JsonNode result,String code){
        if(Set.of("REFERENCE_FAILURE","OTHER").contains(code))return true;
        if("IDENTITY_MISMATCH".equals(code)){if(!result.path("characterIdentity").path("pass").asBoolean())return true;for(JsonNode character:result.path("characters"))if(!character.path("identity").path("pass").asBoolean())return true;return false;}
        for(var entry:METRIC_FAILURE_CODE.entrySet())if(entry.getValue().equals(code)&&!result.path(entry.getKey()).path("pass").asBoolean())return true;
        return false;
    }
    private String metricsFor(String code){StringJoiner names=new StringJoiner("/");METRIC_FAILURE_CODE.forEach((metric,mapped)->{if(mapped.equals(code))names.add(metric);});String value=names.toString();return value.isBlank()?"特殊失败项":value;}
    private void range(JsonNode value,double min,double max,String name){if(!value.isNumber()||value.asDouble()<min||value.asDouble()>max)throw invalid(name+" 超出范围");}
    private IllegalArgumentException invalid(String message){return new IllegalArgumentException("VLM 结构化结果无效："+message);}
}
