package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class QualityDiagnosisService {
    private static final List<String> REPAIR_DIMENSIONS=List.of("FACE","IDENTITY","COSTUME","PROP","ACTION","BLOCKING","CAMERA","BACKGROUND","LIGHTING","LIP_SYNC","STYLE","CONTINUITY");
    public ObjectNode diagnose(JsonNode review){
        String reason=review.path("failureReason").asText(review.path("notes").asText(""));
        String normalized=reason.toLowerCase(Locale.ROOT);LinkedHashSet<String> codes=new LinkedHashSet<>();review.path("failureCodes").forEach(code->codes.add(code.asText()));boolean explicit=!codes.isEmpty();
        if(codes.isEmpty()){
            add(codes,normalized,"IDENTITY_MISMATCH","身份","脸","长相","identity");
            add(codes,normalized,"CHARACTER_COUNT_ERROR","多余人物","多余角色","未经指派","人数","character count");
            add(codes,normalized,"AGE_MISMATCH","年龄","年纪","age");
            add(codes,normalized,"HAIR_MISMATCH","发型","头发","hair");
            add(codes,normalized,"CLOTHING_MISMATCH","衣服","服装","穿着","clothing","wardrobe");
            add(codes,normalized,"PROP_MISMATCH","铃","道具","握","持物","prop");
            add(codes,normalized,"LOCATION_MISMATCH","门","地点","布局","供桌","场景","location");
            add(codes,normalized,"POSITION_MISMATCH","站位","朝向","位置","position");
            add(codes,normalized,"ACTION_MISMATCH","动作","姿势","action");
            add(codes,normalized,"EXPRESSION_MISMATCH","表情","情绪","expression");
            add(codes,normalized,"STYLE_MISMATCH","风格","画风","style");
            add(codes,normalized,"CAMERA_MISMATCH","机位","拍摄方向","镜头方向","俯拍","仰拍","camera");
            add(codes,normalized,"COMPOSITION_ERROR","全身","横幅","构图","景别","主体位置","composition");
            add(codes,normalized,"TEXT_OR_WATERMARK","水印","文字","字幕","watermark");
            add(codes,normalized,"CONTINUITY_MISMATCH","不连续","跳变","断裂","上一镜","前一镜","continuity");
            add(codes,normalized,"REFERENCE_FAILURE","参考图错误","参考图缺失","参考图没有生效","参考失效","reference failure");
        }
        if(codes.isEmpty())codes.add("OTHER");
        String origin=review.path("failureOrigin").asText("PROVIDER_OUTPUT");
        String repair=repair(origin,codes);
        ObjectNode diagnosis=obj().put("failureOrigin",origin).put("failureReason",reason).put("failureCodesSource",explicit?"EXPLICIT":"INFERRED")
            .put("recommendedRepair",review.path("recommendedRepair").asText(repair));
        ArrayNode failures=diagnosis.putArray("failureCodes");codes.forEach(failures::add);diagnosis.set("repairPlan",repairPlan(codes,text(diagnosis,"recommendedRepair")));return diagnosis;
    }

    private ObjectNode repairPlan(Set<String> codes,String repair){
        LinkedHashSet<String> changed=new LinkedHashSet<>(),sections=new LinkedHashSet<>();for(String code:codes){changed.add(dimension(code));sections.add(promptSection(code));}
        ObjectNode plan=obj().put("scope",scope(repair));ArrayNode repairDimensions=plan.putArray("repairDimensions");changed.forEach(repairDimensions::add);ArrayNode preserve=plan.putArray("preserveDimensions");REPAIR_DIMENSIONS.stream().filter(value->!changed.contains(value)).forEach(preserve::add);ArrayNode promptSections=plan.putArray("changedPromptSections");sections.forEach(promptSections::add);return plan;
    }
    static String promptSection(String issue){
        String value=issue.toUpperCase(Locale.ROOT);
        if(value.contains("IDENTITY")||value.contains("CHARACTER_COUNT")||value.contains("AGE")||value.contains("HAIR")||value.contains("CLOTHING")||value.contains("LOCATION"))return "HIGH-RISK CONTINUITY LOCKS";
        if(value.contains("PROP")||value.contains("HAND"))return "PHYSICS / INTERACTION";
        if(value.contains("MOTION")||value.contains("ACTION"))return "TIMED BEATS";
        if(value.contains("CAMERA")||value.contains("COMPOSITION"))return "CAMERA / MOTION PHASE";
        if(value.contains("EXPRESSION")||value.contains("AFFECT"))return "SHOT INTENT / CARRIERS";
        if(value.contains("POSITION")||value.contains("STATE")||value.contains("CONTINUITY"))return "ACTUAL OPENING STATE";
        if(value.contains("REFERENCE"))return "REFERENCE AUTHORITY";
        if(value.contains("TEXT")||value.contains("WATERMARK"))return "OUTPUT CONSTRAINTS";
        if(value.contains("STYLE")||value.contains("LIGHT"))return "DYNAMIC AVOID";
        return "CURRENT ACTION / ENDPOINT";
    }
    private String dimension(String code){return switch(code){case "IDENTITY_MISMATCH","AGE_MISMATCH","HAIR_MISMATCH","CHARACTER_COUNT_ERROR"->"IDENTITY";case "CLOTHING_MISMATCH"->"COSTUME";case "PROP_MISMATCH"->"PROP";case "ACTION_MISMATCH","EXPRESSION_MISMATCH"->"ACTION";case "POSITION_MISMATCH","COMPOSITION_ERROR"->"BLOCKING";case "CAMERA_MISMATCH"->"CAMERA";case "LOCATION_MISMATCH"->"BACKGROUND";case "STYLE_MISMATCH"->"STYLE";case "CONTINUITY_MISMATCH"->"CONTINUITY";default->"CONTINUITY";};}
    private String scope(String repair){return switch(repair){case "REPLAN_SCENE"->"SCENE";case "REPLAN_SHOT"->"SHOT";case "UPDATE_LOCATION_STATE","UPDATE_PROP_STATE","UPDATE_CHARACTER_STATE","REBUILD_PROMPT","RESELECT_REFERENCE"->"CONTEXT";case "MANUAL_FIX"->"MANUAL";default->"TAKE";};}

    private String repair(String origin,Set<String> codes){
        if("REFERENCE_SELECTION".equals(origin))return "RESELECT_REFERENCE";
        if("DIRECTOR_PLAN".equals(origin))return "REPLAN_SCENE";
        if("SHOT_DETAIL".equals(origin))return "REPLAN_SHOT";
        if("STORY_STATE".equals(origin)){
            if(codes.contains("LOCATION_MISMATCH"))return "UPDATE_LOCATION_STATE";
            if(codes.contains("PROP_MISMATCH"))return "UPDATE_PROP_STATE";
            if(!Collections.disjoint(codes,Set.of("IDENTITY_MISMATCH","AGE_MISMATCH","HAIR_MISMATCH","CLOTHING_MISMATCH")))return "UPDATE_CHARACTER_STATE";
            return "MANUAL_FIX";
        }
        if(Set.of("SCRIPT_PARSE","CONTEXT_RESOLUTION","PROMPT_BUILD").contains(origin))return "REBUILD_PROMPT";
        if("PROVIDER_OUTPUT".equals(origin)){
            if(codes.contains("REFERENCE_FAILURE"))return "RESELECT_REFERENCE";
            if(codes.contains("COMPOSITION_ERROR"))return "REPLAN_SHOT";
            return "RETRY_SAME_INPUT";
        }
        return "MANUAL_FIX";
    }
    private void add(Set<String> codes,String value,String code,String... terms){for(String term:terms)if(value.contains(term)){codes.add(code);return;}}
}
