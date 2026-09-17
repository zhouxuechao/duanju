package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.yourapp.drama.production.ProductionModels.Risk;

/** Provider-independent feasibility checks. Invalid plans are replanned before paid media calls. */
public final class ShotComplexityValidator {
    public List<Risk> validate(JsonNode shot){
        List<Risk> risks=new ArrayList<>();
        int actionUnits=shot.path("performancePlan").path("actionUnits").asInt(1);
        int propOperations=shot.path("performancePlan").path("propOperations").size();
        String occlusion=text(shot.path("visibilityPlan"),"occlusion"),detail=text(shot.path("visibilityPlan"),"requiredDetail"),size=text(shot,"shotSize");
        if(actionUnits>1)error(risks,"TOO_MANY_ACTIONS","shot.performancePlan.actionUnits","一个镜头只能承载一个主要动作，请拆镜");
        if(propOperations>1)error(risks,"TOO_MANY_PROP_OPERATIONS","shot.performancePlan.propOperations","一个镜头包含多个道具操作，请按操作阶段拆镜");
        String intent=text(shot,"directorIntent");
        boolean groupComposition=Set.of("EXTREME_WIDE","WIDE","FULL","MEDIUM_FULL").contains(size)&&
            Set.of("SILHOUETTE","ACTION","FULL_BODY").contains(detail)&&
            Set.of("ESTABLISH_SPACE","SHOW_ACTION","EMPHASIZE_EMOTION","TRANSITION","PAYOFF").contains(intent);
        if(shot.path("characterIds").size()>2&&!groupComposition)error(risks,"TOO_MANY_VISIBLE_IDENTITIES","shot.characterIds","身份明确的可见人物超过两位，请拆成建立、反应或插入镜头");
        if("HEAVY".equals(occlusion)&&Set.of("IDENTITY","FULL_BODY","PROP_DETAIL").contains(detail))
            error(risks,"INFORMATION_VISIBILITY_CONFLICT","shot.visibilityPlan","重度遮挡不能同时要求辨认完整身份、全身或精细道具");
        if(Set.of("EXTREME_WIDE","WIDE").contains(size)&&Set.of("IDENTITY","PROP_DETAIL").contains(detail))
            error(risks,"SHOT_SIZE_INFORMATION_CONFLICT","shot.shotSize","远景无法可靠承载身份或精细道具信息");
        if(Set.of("CLOSE_UP","EXTREME_CLOSE_UP").contains(size)&&"FULL_BODY".equals(detail))
            error(risks,"SHOT_SIZE_INFORMATION_CONFLICT","shot.shotSize","特写无法同时展示完整身体");
        double duration=shot.path("duration").asDouble();int dialogueChars=0;
        for(JsonNode line:shot.path("dialogues"))dialogueChars+=text(line,"displayText").codePointCount(0,text(line,"displayText").length());
        double speechSeconds=dialogueChars/4.5+0.35;
        double actionSeconds=actionUnits<=1?0.8:actionUnits*1.2;
        if(duration+0.01<Math.max(speechSeconds,actionSeconds))error(risks,"INSUFFICIENT_DURATION","shot.duration","对白或动作无法在计划时长内自然完成");
        JsonNode start=shot.path("startState").path("characters");
        for(JsonNode actor:shot.path("blocking").path("characters")){
            String id=text(actor,"characterId"),world=text(actor,"worldPosition"),state=text(start.path(id),"position");
            if(!id.isBlank()&&!world.isBlank()&&!state.isBlank()&&!world.equals(state))error(risks,"BLOCKING_STATE_CONFLICT","shot.blocking.characters."+id,"人物调度位置必须与连续性起始状态逐字一致");
        }
        return List.copyOf(risks);
    }
    private static String text(JsonNode n,String field){return n.path(field).asText("").trim();}
    private static void error(List<Risk> risks,String code,String path,String message){risks.add(new Risk(code,"ERROR",path,message));}
}
