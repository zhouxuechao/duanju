package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.domain.JobStatus;
import com.yourapp.drama.domain.ShotStatus;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.production.ProductionJson.*;

@Service
public class ProductionStateMachine {
    private static final List<ShotStatus> ORDER = List.of(ShotStatus.DRAFT, ShotStatus.PLANNED,
        ShotStatus.STORYBOARD_GENERATING, ShotStatus.STORYBOARD_READY, ShotStatus.STORYBOARD_LOCKED,
        ShotStatus.KEYFRAME_GENERATING, ShotStatus.KEYFRAME_READY, ShotStatus.KEYFRAME_QC, ShotStatus.KEYFRAME_LOCKED,
        ShotStatus.VIDEO_GENERATING, ShotStatus.VIDEO_READY, ShotStatus.VIDEO_QC, ShotStatus.VIDEO_LOCKED,
        ShotStatus.AUDIO_READY, ShotStatus.EDITED, ShotStatus.FINISHED);
    private static final Set<ShotStatus> GENERATING = EnumSet.of(ShotStatus.STORYBOARD_GENERATING, ShotStatus.KEYFRAME_GENERATING, ShotStatus.VIDEO_GENERATING);
    private static final Map<JobStatus, Set<JobStatus>> JOB_TRANSITIONS = Map.of(
        JobStatus.QUEUED, EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED),
        JobStatus.RUNNING, EnumSet.of(JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.CANCELLED),
        JobStatus.FAILED, EnumSet.of(JobStatus.RETRY_WAIT),
        JobStatus.RETRY_WAIT, EnumSet.of(JobStatus.QUEUED, JobStatus.CANCELLED),
        JobStatus.SUCCESS, Set.of(), JobStatus.CANCELLED, Set.of());
    private final ObjectMapper mapper;
    public ProductionStateMachine(ObjectMapper mapper) { this.mapper = mapper; }

    public JsonNode transitionShot(JsonNode request) {
        ShotStatus from = ShotStatus.valueOf(required(request, "from"));
        ShotStatus to = ShotStatus.valueOf(required(request, "to"));
        JsonNode evidence = request.path("evidence");
        boolean allowed = ORDER.indexOf(from) >= 0 && ORDER.indexOf(to) == ORDER.indexOf(from) + 1;
        if (from == ShotStatus.PLANNED && to == ShotStatus.KEYFRAME_GENERATING && evidence.path("p1DirectKeyframe").asBoolean(false)) allowed = true;
        if (evidence.path("newVersion").asBoolean(false) && !GENERATING.contains(from)) {
            if (to == ShotStatus.STORYBOARD_GENERATING && ORDER.indexOf(from) >= ORDER.indexOf(ShotStatus.STORYBOARD_READY)) allowed = true;
            if (to == ShotStatus.KEYFRAME_GENERATING && ORDER.indexOf(from) >= ORDER.indexOf(ShotStatus.KEYFRAME_READY)) allowed = true;
            if (to == ShotStatus.VIDEO_GENERATING && ORDER.indexOf(from) >= ORDER.indexOf(ShotStatus.VIDEO_READY)) allowed = true;
        }
        if (to == ShotStatus.FAILED) allowed = from != ShotStatus.FINISHED && from != ShotStatus.DRAFT && from != ShotStatus.FAILED;
        if (to == ShotStatus.NEEDS_REPAIR) allowed = Set.of(ShotStatus.KEYFRAME_QC, ShotStatus.VIDEO_QC, ShotStatus.KEYFRAME_LOCKED,
            ShotStatus.VIDEO_LOCKED, ShotStatus.AUDIO_READY, ShotStatus.EDITED, ShotStatus.FINISHED).contains(from);
        if (to == ShotStatus.PROVIDER_URL_EXPIRED) allowed = Set.of(ShotStatus.KEYFRAME_LOCKED, ShotStatus.VIDEO_GENERATING).contains(from);
        if (from == ShotStatus.PROVIDER_URL_EXPIRED) allowed = to == ShotStatus.KEYFRAME_GENERATING;
        if (from == ShotStatus.FAILED || from == ShotStatus.NEEDS_REPAIR) {
            String repairStage = text(request, "repairStage");
            ShotStatus retryFrom = text(request,"retryFrom").isBlank()?null:ShotStatus.valueOf(text(request,"retryFrom"));
            ShotStatus repairTarget = switch (repairStage) {
                case "STORYBOARD" -> ShotStatus.STORYBOARD_GENERATING;
                case "KEYFRAME", "IDENTITY", "LOOK", "PROP", "LOCATION" -> ShotStatus.KEYFRAME_GENERATING;
                case "VIDEO", "MOTION" -> ShotStatus.VIDEO_GENERATING;
                case "AUDIO", "TTS" -> ShotStatus.VIDEO_LOCKED;
                case "TIMELINE", "RENDER" -> ShotStatus.AUDIO_READY;
                default -> null;
            };
            allowed = (repairTarget != null && to == repairTarget) || (retryFrom != null && to == retryFrom && (GENERATING.contains(to) || Set.of(ShotStatus.VIDEO_LOCKED, ShotStatus.AUDIO_READY).contains(to)));
        }
        if (!allowed) throw new IllegalArgumentException("不允许的镜头状态迁移: " + from + " → " + to);
        if (to == ShotStatus.STORYBOARD_LOCKED && !evidence.path("reviewPassed").asBoolean(false)) fail("分镜锁定需要审核通过");
        if (to == ShotStatus.KEYFRAME_LOCKED && !evidence.path("qcPassed").asBoolean(false)) fail("Keyframe 锁定需要 QC 通过");
        if (to == ShotStatus.VIDEO_GENERATING && (!evidence.path("keyframeLocked").asBoolean(false) || !evidence.path("providerUrlValid").asBoolean(false))) fail("视频生成需要已锁定 Keyframe 和有效的原始 provider_url");
        if (to == ShotStatus.VIDEO_LOCKED && (!evidence.path("qcPassed").asBoolean(false) || !evidence.path("endStateReviewed").asBoolean(false))) fail("Take 锁定需要 QC 通过和结束状态复核");
        if (to == ShotStatus.AUDIO_READY && !evidence.path("audioReady").asBoolean(false)) fail("音频阶段需要对白音频完成，或明确无对白");
        if (to == ShotStatus.EDITED && !evidence.path("timelineValid").asBoolean(false)) fail("剪辑完成需要时间线校验通过");
        if (to == ShotStatus.FINISHED && !evidence.path("renderSucceeded").asBoolean(false)) fail("成片完成需要渲染成功");
        ObjectNode result = mapper.createObjectNode().put("from", from.name()).put("to", to.name()).put("allowed", true);
        var invalidate = result.putArray("invalidate");
        if (to == ShotStatus.KEYFRAME_GENERATING && (from == ShotStatus.NEEDS_REPAIR || from == ShotStatus.FAILED || from == ShotStatus.PROVIDER_URL_EXPIRED))
            List.of("KEYFRAME_LOCK", "VIDEO_TAKES", "LIPSYNC", "TIMELINE_RENDER").forEach(invalidate::add);
        if (to == ShotStatus.VIDEO_GENERATING && (from == ShotStatus.NEEDS_REPAIR || from == ShotStatus.FAILED))
            List.of("VIDEO_TAKE_LOCK", "LIPSYNC", "TIMELINE_RENDER").forEach(invalidate::add);
        return result;
    }

    public JsonNode transitionJob(JsonNode request) {
        JobStatus from = JobStatus.valueOf(required(request, "from"));
        JobStatus to = JobStatus.valueOf(required(request, "to"));
        if (!JOB_TRANSITIONS.get(from).contains(to)) fail("不允许的任务状态迁移: " + from + " → " + to);
        if (to == JobStatus.RETRY_WAIT && request.path("attempt").asInt(0) >= request.path("maxAttempts").asInt(3)) fail("已达到最大尝试次数");
        if (to == JobStatus.FAILED && text(request, "failureReason").isBlank()) fail("失败必须记录 failureReason");
        return mapper.createObjectNode().put("from", from.name()).put("to", to.name()).put("allowed", true)
            .put("terminal", to == JobStatus.SUCCESS || to == JobStatus.CANCELLED)
            .put("incrementAttempt", from == JobStatus.QUEUED && to == JobStatus.RUNNING);
    }
    private void fail(String reason) { throw new IllegalArgumentException(reason); }
}
