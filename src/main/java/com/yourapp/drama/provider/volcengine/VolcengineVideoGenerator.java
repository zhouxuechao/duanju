package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import com.yourapp.drama.production.VideoModelProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class VolcengineVideoGenerator implements VideoGenerator {
    private final ArkHttpClient http;
    private final VolcengineProperties properties;
    private final ProviderCapabilityRegistry capabilities;
    public VolcengineVideoGenerator(ArkHttpClient http, VolcengineProperties properties) {
        this(http,properties,new ProviderCapabilityRegistry());
    }
    public VolcengineVideoGenerator(ArkHttpClient http, VolcengineProperties properties,ProviderCapabilityRegistry capabilities) {this.http=http;this.properties=properties;this.capabilities=capabilities;}
    @Override public Submission submit(VideoRequest request) {
        Map<String,Object> body=requestBody(request);
        ArkHttpClient.Response response = http.exchange("POST", "/contents/generations/tasks", body, true);
        String id = ArkHttpClient.text(response.json().path("id"));
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}"))
            throw new ProviderException("MISSING_TASK_ID", "提交未返回可查询的任务 ID；请核对模型后台，勿重复付费提交", response.requestId(), 200, false, true);
        return new Submission(id, response.requestId(), false);
    }
    /** Exact sanitized provider payload used for audit evidence before a billable submit. */
    public Map<String,Object> requestBodySnapshot(VideoRequest request){return Map.copyOf(requestBody(request));}
    private Map<String,Object> requestBody(VideoRequest request){
        ProviderInputs.prompt(request.prompt());
        boolean firstFrame = request.firstFrameUrl() != null && !request.firstFrameUrl().isBlank();
        validateLimits(request,firstFrame);
        boolean fullModal = request.references().stream().anyMatch(r -> r.role() != null && r.role().startsWith("reference_"));
        if (fullModal && (firstFrame || request.references().stream().anyMatch(r -> "first_frame".equals(r.role()) || "last_frame".equals(r.role()))))
            throw ProviderException.invalid("REFERENCE_ROUTE_CONFLICT", "首尾帧路线与全模态参考路线不能同时提交；请选择独立首帧或把关键帧作为 reference_image");
        if (request.references().stream().anyMatch(r -> "first_frame".equals(r.role())))
            throw ProviderException.invalid("DUPLICATE_FIRST_FRAME", "首帧必须通过 firstFrameUrl 传入");
        if (!firstFrame && request.references().stream().anyMatch(r -> "last_frame".equals(r.role())))
            throw ProviderException.invalid("FIRST_FRAME_REQUIRED", "使用尾帧前必须提供首帧");
        Map<String, Object> body = ProviderInputs.options(request.options(), Set.of("duration", "frames", "resolution", "ratio", "seed", "watermark", "camera_fixed", "generate_audio", "return_last_frame", "execution_expires_after", "service_tier", "priority", "draft"));
        String model=model(request);VideoModelProfile profile=capabilities.profile(model);body.put("model",model);
        if (body.get("duration") instanceof Number value)try{body.put("duration",profile.providerDuration(value.doubleValue()));}catch(IllegalArgumentException error){throw ProviderException.invalid("DURATION_UNSUPPORTED",error.getMessage());}
        body.putIfAbsent("resolution",properties.getVideoResolution());
        try{profile.requireResolution(body.get("resolution").toString());}catch(IllegalArgumentException error){throw ProviderException.invalid("RESOLUTION_UNSUPPORTED",error.getMessage());}
        if(firstFrame)body.remove("ratio");
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.prompt()));
        if (firstFrame) { ProviderInputs.imageUrl(request.firstFrameUrl(), false); content.add(media("image_url", request.firstFrameUrl(), "first_frame")); }
        for (Reference ref : request.references()) {
            if (ref.type() == null || ref.role() == null || !Set.of("image_url", "video_url", "audio_url").contains(ref.type()))
                throw ProviderException.invalid("INVALID_REFERENCE_TYPE", "参考素材类型或角色无效");
            Set<String> roles = switch (ref.type()) { case "image_url" -> Set.of("reference_image", "last_frame"); case "video_url" -> Set.of("reference_video"); default -> Set.of("reference_audio"); };
            if (!roles.contains(ref.role())) throw ProviderException.invalid("INVALID_REFERENCE_ROLE", "参考素材角色与类型不匹配");
            ProviderInputs.imageUrl(ref.url(), ref.role().startsWith("reference_"));
            content.add(media(ref.type(), ref.url(), ref.role()));
        }
        body.put("content", content);
        return body;
    }
    @Override public VideoTask poll(String taskId) {
        ProviderInputs.taskId(taskId);
        ArkHttpClient.Response response = http.exchange("GET", "/contents/generations/tasks/" + taskId, null, false);
        JsonNode result = response.json();
        if (result.hasNonNull("id") && !taskId.equals(result.path("id").asText()))
            throw new ProviderException("TASK_ID_MISMATCH", "返回的视频任务 ID 与查询不一致", response.requestId(), 200, false, false);
        Status status;
        try { status = Status.valueOf(result.path("status").asText().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ProviderException("UNKNOWN_TASK_STATUS", "模型返回未知任务状态", response.requestId(), 200, false, false); }
        String url = ArkHttpClient.text(result.path("content").path("video_url"));
        if (status == Status.SUCCEEDED && url == null)
            throw new ProviderException("MISSING_VIDEO_URL", "成功任务没有返回视频 URL", response.requestId(), 200, true, false);
        Instant completed = ProviderExpiry.timestamp(result, "updated_at");
        Instant expires = url == null ? null : ProviderExpiry.expiry(result, url, completed, properties.getVideoUrlTtl());
        return new VideoTask(taskId, status, url, expires, response.requestId(), ArkHttpClient.text(result.path("error").path("code")),
                ArkHttpClient.text(result.path("error").path("message")), false);
    }
    @Override public void cancel(String taskId) {
        // DELETE also deletes completed records; polling first keeps this operation cancellation-only.
        VideoTask task = poll(taskId);
        if (task.status() == Status.CANCELLED) return;
        if (task.status() != Status.QUEUED)
            throw new ProviderException("CANCEL_NOT_ALLOWED", "火山仅支持取消排队中的任务；当前状态：" + task.status(), task.requestId(), 409, false, false);
        http.exchange("DELETE", "/contents/generations/tasks/" + taskId, null, false);
    }
    private Map<String, Object> media(String type, String url, String role) { return Map.of("type", type, type, Map.of("url", url), "role", role); }
    private void validateLimits(VideoRequest request,boolean firstFrame){VideoModelProfile.ReferenceLimits limits=capabilities.profile(model(request)).hardLimits();int images=firstFrame?1:0,videos=0,audios=0;for(Reference ref:request.references())switch(ref.type()){case "image_url"->images++;case "video_url"->videos++;case "audio_url"->audios++;default->{}}int total=images+videos+audios;if(images>limits.images()||videos>limits.videos()||audios>limits.audios()||total>limits.total())throw ProviderException.invalid("REFERENCE_LIMIT_EXCEEDED","REFERENCE_LIMIT_EXCEEDED: 当前模型引用数量超过硬限制 images="+images+", videos="+videos+", audios="+audios+", total="+total);}
    private String model(VideoRequest request){return request.modelId()==null||request.modelId().isBlank()?properties.getVideoModel():request.modelId();}
}
