package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.EditorialTiming;
import com.yourapp.drama.production.EditingEngine;
import com.yourapp.drama.production.EditOperation;
import com.yourapp.drama.production.ProductionModels;
import com.yourapp.drama.production.ContinuityCompatibilityEvaluator;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class TimelineQualityService {
    private final DocumentStore store;
    private final ContinuityCompatibilityEvaluator continuity;
    public TimelineQualityService(DocumentStore store,ContinuityCompatibilityEvaluator continuity){this.store=store;this.continuity=continuity;}

    public ObjectNode review(String timelineId){return store.transaction(()->{
        ObjectNode timeline=store.getForUpdate(TIMELINE,timelineId);long contentRevision=timeline.path("contentRevision").asLong(1);
        ArrayNode failures=JsonNodeFactory.instance.arrayNode(),details=JsonNodeFactory.instance.arrayNode();
        List<ObjectNode> items=store.list(TIMELINE_ITEM,project(timeline),timelineId),videos=items.stream().filter(i->"VIDEO".equals(text(i,"track"))).sorted(Comparator.comparingLong(i->i.path("startMs").asLong())).toList();
        if(items.stream().anyMatch(item->item.path("timelineRelinkRequired").asBoolean()))failure(failures,details,"TIMELINE_RELINK_REQUIRED","旧时间线片段无法安全绑定到唯一画面，请人工重新选择视频片段");
        ArrayNode editingItems=JsonNodeFactory.instance.arrayNode();items.forEach(editingItems::add);List<ProductionModels.Risk> editingRisks=new ArrayList<>();EditingEngine.validate(editingItems,editingRisks);for(ProductionModels.Risk risk:editingRisks)if("ERROR".equals(risk.severity()))failure(failures,details,risk.code(),risk.message());
        if(videos.isEmpty())failure(failures,details,"VIDEO_REQUIRED","时间线没有视频轨");
        long cursor=0;ObjectNode previousShot=null,previousTake=null;int videoIndex=0;
        for(ObjectNode item:videos){long start=item.path("startMs").asLong(-1),duration=item.path("durationMs").asLong(-1),sourceIn=item.path("sourceInMs").asLong(-1),sourceOut=item.path("sourceOutMs").asLong(-1);String transition=item.path("transition").asText("CUT").toUpperCase(Locale.ROOT);long transitionDuration=item.path("transitionDurationMs").asLong(0);
            if(!Set.of("CUT","MATCH_CUT","CROSS_DISSOLVE","FADE_TO_BLACK").contains(transition))failure(failures,details,"TRANSITION_INVALID","时间线包含不支持的镜头衔接类型");
            if(videoIndex==0&&(!"CUT".equals(transition)||transitionDuration!=0))failure(failures,details,"FIRST_TRANSITION_INVALID","第一个镜头必须从直接切换开始");
            if("CROSS_DISSOLVE".equals(transition)&&(transitionDuration<100||transitionDuration>1000||transitionDuration>=duration))failure(failures,details,"TRANSITION_DURATION_INVALID","叠化时长必须为 0.1～1 秒且短于当前片段");
            long overlap=videoIndex>0&&"CROSS_DISSOLVE".equals(transition)?transitionDuration:0,expectedStart=videoIndex==0?0:cursor-overlap;
            if(start!=expectedStart)failure(failures,details,start<expectedStart?"VIDEO_OVERLAP":"VIDEO_GAP","视频轨在 "+expectedStart+"ms 处不连续");
            long pauseDuration=EditingEngine.hasOperation(item,EditOperation.PAUSE)?item.path("pauseDurationMs").asLong(-1):0,sourceDuration=duration-Math.max(0,pauseDuration);if(duration<EditorialTiming.MIN_SHOT_MS||sourceIn<0||pauseDuration<0||sourceDuration<=0||sourceOut-sourceIn!=sourceDuration)failure(failures,details,"VIDEO_TRIM_INVALID","视频剪辑源区间与输出时长、停帧时长不一致");
            ObjectNode take=safe(VIDEO_TAKE,text(item,"videoTakeId"));if(take==null)failure(failures,details,"VIDEO_TAKE_MISSING","时间线引用的视频版本不存在");else{
                long actual=take.path("actualDurationMs").asLong(0);if(actual<=0&&!take.path("simulated").asBoolean())failure(failures,details,"MEDIA_DURATION_UNKNOWN","视频缺少媒体探测时长");if(actual>0&&sourceOut>actual)failure(failures,details,"VIDEO_TRIM_OUT_OF_RANGE","视频出点超过素材实际时长");
                if(!take.path("selected").asBoolean()||!take.path("locked").asBoolean()||!"PASSED".equals(text(take,"qcStatus")))failure(failures,details,"VIDEO_TAKE_NOT_APPROVED","时间线使用了未采用或未通过质检的视频");
                if(take.path("observedDifferences").isArray()&&!take.path("observedDifferences").isEmpty()&&!"ACCEPT_CANONICAL".equals(text(take,"deviationDecision")))failure(failures,details,"OBSERVED_STATE_UNRESOLVED","视频实拍状态与计划状态的差异尚未处理");
            }
            ObjectNode shot=safe(SHOT,text(item,"shotId"));if(shot==null)failure(failures,details,"SHOT_MISSING","时间线项目缺少来源镜头");else{if(previousShot!=null&&"CONTINUOUS".equals(text(shot,"relationToPrevious"))){for(ProductionModels.Risk risk:continuity.comparePlanned(previousShot.path("endState"),shot.path("startState")))failure(failures,details,risk.code(),risk.message());for(ProductionModels.Risk risk:continuity.compareObserved(previousTake==null?MissingNode.getInstance():previousTake,shot.path("startState")))failure(failures,details,risk.code(),risk.message());for(ProductionModels.Risk risk:continuity.compareBlocking(previousShot.path("blocking"),shot.path("blocking")))failure(failures,details,risk.code(),risk.message());}if("FADE_TO_BLACK".equals(transition)&&!allowsFadeToBlack(shot))failure(failures,details,"TRANSITION_SEMANTIC_INVALID","淡黑只能用于明确的时间跳跃、章节边界、梦境或情绪停顿");}
            previousShot=shot;previousTake=take;cursor=start+Math.max(0,duration);videoIndex++;
        }
        for(ObjectNode item:items)if(!"VIDEO".equals(text(item,"track"))){long start=item.path("startMs").asLong(-1),duration=item.path("durationMs").asLong(-1);if(start<0||duration<=0||start+duration>cursor)failure(failures,details,"AUDIO_TIMING_INVALID","音频超出成片范围");if("DIALOGUE".equals(text(item,"track"))){ObjectNode clip=safe(AUDIO_CLIP,text(item,"audioClipId"));if(clip==null||!clip.path("selected").asBoolean()||!clip.path("locked").asBoolean())failure(failures,details,"DIALOGUE_AUDIO_NOT_APPROVED","对白引用了未采用的配音");}}
        if(timeline.path("durationMs").asLong(-1)!=cursor)failure(failures,details,"TIMELINE_DURATION_MISMATCH","时间线总时长与视频轨不一致");
        if(timeline.path("cueCount").asInt()>0&&text(timeline,"srt").isBlank())failure(failures,details,"SUBTITLE_MISSING","存在对白但没有字幕文件");
        if("FAILED".equals(text(timeline,"subtitleQaStatus"))||timeline.path("subtitleIssues").isArray()&&!timeline.path("subtitleIssues").isEmpty())failure(failures,details,"SUBTITLE_QUALITY_FAILED","字幕行长或阅读速度不符合当前字幕样式");
        if(text(timeline,"previewUrl").isBlank()||timeline.path("previewStale").asBoolean()||timeline.path("previewTimelineRevision").asLong(-1)!=contentRevision)failure(failures,details,"PREVIEW_STALE","请先生成当前剪辑版本的预览片");
        ObjectNode qa=obj().put("passed",failures.isEmpty()).put("contentRevision",contentRevision).put("checkedAt",Instant.now().toString()).put("videoTrackContinuous",!contains(failures,"VIDEO_GAP")&&!contains(failures,"VIDEO_OVERLAP")).put("audioWithinBounds",!contains(failures,"AUDIO_TIMING_INVALID")).put("subtitleReady",!contains(failures,"SUBTITLE_MISSING")).put("sourceVersionsApproved",!contains(failures,"VIDEO_TAKE_NOT_APPROVED")&&!contains(failures,"DIALOGUE_AUDIO_NOT_APPROVED"));qa.set("failureCodes",failures);qa.set("details",details);
        ObjectNode next=timeline.deepCopy().put("timelineQaStatus",qa.path("passed").asBoolean()?"PASSED":"FAILED");next.set("timelineQa",qa);store.update(TIMELINE,timelineId,revision(timeline),next);return qa;
    });}
    private ObjectNode safe(com.yourapp.drama.persistence.ResourceKind kind,String documentId){if(documentId.isBlank())return null;return store.find(kind,documentId).orElse(null);}
    private boolean allowsFadeToBlack(ObjectNode shot){String relation=text(shot,"relationToPrevious").toUpperCase(Locale.ROOT),timeRelation=text(shot,"timeRelationToPrevious").toUpperCase(Locale.ROOT),purpose=text(shot,"purpose").toUpperCase(Locale.ROOT);return Set.of("TIME_JUMP","CHAPTER_BREAK","DREAM","FLASHBACK","EMOTIONAL_PAUSE").contains(relation)||Set.of("TIME_JUMP","NEXT_DAY","DREAM","FLASHBACK","CHAPTER_BREAK").contains(timeRelation)||purpose.contains("停顿")||purpose.contains("章节");}
    private void failure(ArrayNode codes,ArrayNode details,String code,String message){if(!contains(codes,code))codes.add(code);details.add(obj().put("code",code).put("message",message));}
    private boolean contains(ArrayNode codes,String code){for(JsonNode value:codes)if(code.equals(value.asText()))return true;return false;}
}
