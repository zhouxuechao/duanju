package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.VideoGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Chooses one provider request route and prevents first-frame/reference-video conflicts. */
@Component
public class VideoRequestRouteResolver {
    public enum Route { FIRST_FRAME, FULL_MODAL_REFERENCE, CONTINUATION_LAST_FRAME, UNSUPPORTED }
    public record Resolved(Route route, String firstFrameUrl, List<VideoGenerator.Reference> references) {
        public Resolved { references = List.copyOf(references); }
    }

    public Resolved resolve(String shotRelation, JsonNode capabilities, JsonNode keyframe,
                            JsonNode previousTake, JsonNode referenceBindings) {
        String keyframeUrl = text(keyframe, "providerUrl");
        if (!"SEAMLESS_CONTINUATION".equals(shotRelation)) {
            if (keyframeUrl.isBlank()) throw unsupported("首镜或切镜缺少已批准 Keyframe providerUrl");
            return new Resolved(Route.FIRST_FRAME, keyframeUrl, List.of());
        }
        if (!accepted(previousTake)) throw unsupported("连续镜头缺少 selected + locked + QC PASSED 的 previousTake");

        String previousVideo = bindingUrl(referenceBindings, ReferenceBinding.Role.PREVIOUS_TAKE);
        if (previousVideo.isBlank()) previousVideo = text(previousTake, "videoUrl");
        boolean supportsVideo = capabilities.path("supportsReferenceVideo").asBoolean(false)
                && capabilities.path("maxVideoRefs").asInt(0) > 0;
        if (supportsVideo && !previousVideo.isBlank()) {
            List<VideoGenerator.Reference> references = new ArrayList<>();
            references.add(new VideoGenerator.Reference("video_url", previousVideo, "reference_video"));
            if (capabilities.path("maxImageRefs").asInt(0) > 0) {
                String image = bindingUrl(referenceBindings, ReferenceBinding.Role.KEYFRAME_PROVIDER);
                if (image.isBlank()) image = keyframeUrl;
                if (!image.isBlank()) references.add(new VideoGenerator.Reference("image_url", image, "reference_image"));
            }
            return new Resolved(Route.FULL_MODAL_REFERENCE, null, references);
        }

        String lastFrame = text(previousTake, "lastFrameUrl");
        if (lastFrame.isBlank()) lastFrame = bindingUrl(referenceBindings, ReferenceBinding.Role.PREVIOUS_LAST_FRAME);
        if (capabilities.path("supportsStartEndFrame").asBoolean(false) && !lastFrame.isBlank())
            return new Resolved(Route.CONTINUATION_LAST_FRAME, lastFrame, List.of());
        throw unsupported("Provider 不支持可用的 previousTake reference_video，且没有可用 previousLastFrame");
    }

    private boolean accepted(JsonNode take) {
        return take.path("selected").asBoolean() && take.path("locked").asBoolean()
                && (take.path("qcPassed").asBoolean() || "PASSED".equals(text(take, "qcStatus")));
    }
    private String bindingUrl(JsonNode bindings, ReferenceBinding.Role expected) {
        if (bindings != null && bindings.isArray()) for (JsonNode binding : bindings) {
            try {
                if (ReferenceBinding.Role.from(text(binding, "role")) == expected) return text(binding, "url");
            } catch (IllegalArgumentException ignored) {
                // Non-video authority bindings are intentionally ignored by the route boundary.
            }
        }
        return "";
    }
    private String text(JsonNode node, String field) { return node == null ? "" : node.path(field).asText("").trim(); }
    private IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException("VIDEO_ROUTE_UNSUPPORTED: " + reason);
    }
}
