package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class QualityDiagnosisService {
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
        ArrayNode failures=diagnosis.putArray("failureCodes");codes.forEach(failures::add);return diagnosis;
    }

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
