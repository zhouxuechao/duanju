package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.VideoGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Chooses one provider request route and prevents first-frame/reference-video conflicts. */
@Component
public class VideoRequestRouteResolver {
    public enum Route { FIRST_FRAME, NATIVE_FIRST_FRAME, NATIVE_FIRST_LAST_FRAME, SEMANTIC_REFERENCES, STORYBOARD_GUIDED, FULL_MODAL_REFERENCE, CONTINUATION_LAST_FRAME, UNSUPPORTED }
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

    public Resolved resolve(VideoTaskType taskType,ProviderTaskLockMode lockMode,VideoModelProfile profile,
                            JsonNode keyframes,JsonNode previousTake,JsonNode referenceBindings){
        if(taskType==null)throw unsupported("VIDEO_TASK_TYPE_REQUIRED");
        if(!taskType.implemented())throw new IllegalArgumentException("VIDEO_TASK_NOT_IMPLEMENTED: "+taskType);
        if(taskType.lockMode()!=lockMode)throw new IllegalArgumentException("VIDEO_TASK_LOCK_MODE_MISMATCH: "+taskType+" requires "+taskType.lockMode());
        if(!profile.supportedTaskTypes().contains(taskType))throw unsupported("MODEL_PROFILE_TASK_UNSUPPORTED: "+taskType);
        List<VideoGenerator.Reference> semantic=new ArrayList<>();
        String start="",end="";
        if(keyframes!=null&&keyframes.isArray())for(JsonNode frame:keyframes){KeyframeSemanticRole role=KeyframeSemanticRole.from(text(frame,"semanticRole"));String url=text(frame,"providerUrl");if(url.isBlank())continue;
            if(role==KeyframeSemanticRole.START_FRAME)start=url;else if(role==KeyframeSemanticRole.END_FRAME)end=url;else semantic.add(new VideoGenerator.Reference("image_url",url,"reference_image"));
        }
        if(taskType==VideoTaskType.FIRST_FRAME_GENERATE){if(start.isBlank())throw unsupported("START_FRAME_REQUIRED");return new Resolved(Route.NATIVE_FIRST_FRAME,start,List.of());}
        if(taskType==VideoTaskType.FIRST_LAST_FRAME_GENERATE){if(start.isBlank()||end.isBlank())throw unsupported("START_AND_END_FRAME_REQUIRED");return new Resolved(Route.NATIVE_FIRST_LAST_FRAME,start,List.of(new VideoGenerator.Reference("image_url",end,"last_frame")));}
        if(taskType==VideoTaskType.KEYFRAME_GENERATE){
            if(!profile.supportsSemanticKeyframes())throw unsupported("SEMANTIC_KEYFRAMES_UNSUPPORTED");
            if(!start.isBlank())semantic.addFirst(new VideoGenerator.Reference("image_url",start,"reference_image"));if(!end.isBlank())semantic.add(new VideoGenerator.Reference("image_url",end,"reference_image"));
            appendSemanticBindings(referenceBindings,semantic);return new Resolved(Route.SEMANTIC_REFERENCES,null,semantic);
        }
        if(taskType==VideoTaskType.STORYBOARD_GUIDED){if(!profile.supportsStoryboard())throw unsupported("STORYBOARD_UNSUPPORTED");appendSemanticBindings(referenceBindings,semantic);return new Resolved(Route.STORYBOARD_GUIDED,null,semantic);}
        if(taskType==VideoTaskType.REFERENCE_GENERATE){
            if(!accepted(previousTake))throw unsupported("REFERENCE_GENERATE_REQUIRES_ACCEPTED_PREVIOUS_TAKE");appendSemanticBindings(referenceBindings,semantic);
            String previousVideo=text(previousTake,"videoUrl");
            if(previousVideo.isBlank())throw unsupported("PREVIOUS_VIDEO_REQUIRED");
            if(semantic.stream().noneMatch(ref->"video_url".equals(ref.type())&&previousVideo.equals(ref.url())))
                semantic.addFirst(new VideoGenerator.Reference("video_url",previousVideo,"reference_video"));
            return new Resolved(Route.FULL_MODAL_REFERENCE,null,semantic);
        }
        appendSemanticBindings(referenceBindings,semantic);return new Resolved(Route.SEMANTIC_REFERENCES,null,semantic);
    }

    private void appendSemanticBindings(JsonNode bindings,List<VideoGenerator.Reference> refs){
        if(bindings==null||!bindings.isArray())return;for(JsonNode binding:bindings){String url=text(binding,"url"),type=text(binding,"mediaType");if(type.isBlank())type=text(binding,"type");if(url.isBlank())continue;String providerRole=switch(type){case "video_url"->"reference_video";case "audio_url"->"reference_audio";default->"reference_image";};refs.add(new VideoGenerator.Reference(type.isBlank()?"image_url":type,url,providerRole));}
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
