package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.domain.ShotRelation;
import java.util.*;
import static com.yourapp.drama.production.ProductionJson.*;

/** Last deterministic gate before a billable video submission is even queued. */
public final class SequenceBoundaryGate {
    public void validate(JsonNode request,List<JsonNode> bindings,List<String> carriers){
        JsonNode shot=shotNode(request);String relation=text(shot,"relationToPrevious");
        if(text(shot,"feltIntent").isBlank())fail("FELT_INTENT_REQUIRED","镜头缺少观众感受目标");
        if(carriers.isEmpty())fail("FELT_INTENT_CARRIER_REQUIRED","feltIntent 没有可见表演、摄影或光线载体");
        if(text(shot,"endpoint").isBlank()&&(!shot.path("endState").isObject()||shot.path("endState").isEmpty()))fail("ENDPOINT_REQUIRED","镜头必须有明确结束状态");
        JsonNode previous=request.path("previousTake");
        if("CONTINUOUS".equals(relation)){
            if(!previous.path("locked").asBoolean()||!previous.path("selected").asBoolean()||!previous.path("qcPassed").asBoolean())fail("PREVIOUS_TAKE_NOT_ACCEPTED","连续镜头的上一条视频必须已选择、锁定并通过 QC");
            if(!previous.path("observedState").isObject()||previous.path("observedState").isEmpty())fail("PREVIOUS_OBSERVED_STATE_REQUIRED","连续镜头缺少上一条实际结束状态");
            if(observedMismatch(previous.path("observedState"),request.path("previousState"),shot))fail("PREVIOUS_OBSERVED_STATE_MISMATCH","当前镜头计划起点与上一条已接受视频的实际结束状态不一致");
        }
        Set<String> completed=upper(shot.path("completedBeats")),reserved=upper(shot.path("reservedFutureBeats"));String action=text(shot,"action").toUpperCase(Locale.ROOT);
        String currentBeat=text(shot.path("currentBeat"),"beatId").trim().toUpperCase(Locale.ROOT);
        if(!currentBeat.isBlank()&&completed.contains(currentBeat))fail("COMPLETED_BEAT_REPEATED","当前节拍已被标为完成 "+currentBeat);
        if(!currentBeat.isBlank()&&reserved.contains(currentBeat))fail("RESERVED_BEAT_EARLY","当前节拍仍被标为未来节拍 "+currentBeat);
        for(String beat:completed)if(!beat.isBlank()&&action.contains(beat))fail("COMPLETED_BEAT_REPEATED","当前动作重复了已完成节拍 "+beat);
        for(String beat:reserved)if(!beat.isBlank()&&action.contains(beat))fail("RESERVED_BEAT_EARLY","当前动作提前执行未来节拍 "+beat);
        int depth=previous.path("continuationDepth").asInt(0),max=request.path("sceneContinuityPolicy").path("maxContinuationDepth").asInt(2);
        if("CONTINUOUS".equals(relation)&&depth>=max&&(!request.path("reanchorPlan").isObject()||request.path("reanchorPlan").isEmpty()))fail("REANCHOR_REQUIRED","连续生成深度已达到上限，必须从 Canonical References 重新锚定");
        Map<String,Integer> authority=new HashMap<>();for(JsonNode binding:bindings){int priority=binding.path("authorityPriority").asInt();String subject=text(binding,"subjectId");if(subject.isBlank())subject=text(binding,"entityId");if(subject.isBlank())subject=text(binding,"sourceResourceId");if(subject.isBlank())subject="GLOBAL";for(JsonNode control:binding.path("controls")){String key=subject+"|"+control.asText();Integer old=authority.putIfAbsent(key,priority);if(old!=null&&old==priority)fail("REFERENCE_AUTHORITY_CONFLICT","同一主体的同一维度存在两个同级权威："+key);}}
    }
    private Set<String> upper(JsonNode array){Set<String> out=new LinkedHashSet<>();if(array.isArray())array.forEach(v->out.add(v.asText("").trim().toUpperCase(Locale.ROOT)));return out;}
    private boolean observedMismatch(JsonNode observed,JsonNode planned,JsonNode shot){
        for(String field:List.of("locationId","time","lighting","spatialRelations"))if(observed.has(field)&&!contains(planned.path(field),observed.path(field)))return true;
        for(JsonNode id:shot.path("characterIds"))if(observed.path("characters").has(id.asText())&&!contains(planned.path("characters").path(id.asText()),observed.path("characters").path(id.asText())))return true;
        for(JsonNode id:shot.path("propIds"))if(observed.path("props").has(id.asText())&&!contains(planned.path("props").path(id.asText()),observed.path("props").path(id.asText())))return true;
        return false;
    }
    private boolean contains(JsonNode planned,JsonNode observed){if(observed.isObject()){if(!planned.isObject())return false;Iterator<Map.Entry<String,JsonNode>> fields=observed.fields();while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();if(!planned.has(field.getKey())||!contains(planned.path(field.getKey()),field.getValue()))return false;}return true;}return planned.equals(observed);}
    private void fail(String code,String message){throw new IllegalArgumentException(code+"："+message);}
}
