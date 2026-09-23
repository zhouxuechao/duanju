package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

import static com.yourapp.drama.workflow.Documents.obj;

/** Immutable model-specific limits. Provider differences are resolved here, not in workflow code. */
public record VideoModelProfile(
        String modelId, String family, String profileVersion, String capabilityFingerprint,
        boolean supportsReferenceVideo, boolean supportsReferenceAudio, boolean supportsNativeAudio,
        boolean supportsStartFrame, boolean supportsEndFrame, boolean supportsSemanticKeyframes,
        boolean supportsStoryboard, boolean supportsVideoEdit, boolean supportsAudioEdit,
        boolean supportsExtension, boolean supportsWhiteModel,
        ReferenceLimits hardLimits, ReferenceLimits recommendedLimits,
        double maxReferenceVideoSeconds,double maxReferenceAudioSeconds,
        String durationMode,int minDuration,int maxDuration,int durationStep,
        List<Integer> supportedDurations,List<String> supportedRatios,List<String> supportedResolutions,List<String> supportedOutputFormats, Set<VideoTaskType> supportedTaskTypes) {
    public record ReferenceLimits(int images,int videos,int audios,int total) {}
    public VideoModelProfile {
        supportedDurations=List.copyOf(supportedDurations);supportedRatios=List.copyOf(supportedRatios);supportedResolutions=List.copyOf(supportedResolutions);supportedOutputFormats=List.copyOf(supportedOutputFormats);supportedTaskTypes=Set.copyOf(supportedTaskTypes);
    }
    public ObjectNode toJson(){
        ObjectNode n=obj().put("version",profileVersion).put("modelId",modelId).put("modelFamily",family)
                .put("capabilityFingerprint",capabilityFingerprint).put("source","MODEL_PROFILE").put("verificationStatus","STATIC_UNVERIFIED")
                .put("supportsMultipleImages",hardLimits.images()>1).put("supportsReferenceImage",hardLimits.images()>0).put("supportsReferenceVideo",supportsReferenceVideo)
                .put("supportsReferenceAudio",supportsReferenceAudio).put("supportsNativeAudio",supportsNativeAudio)
                .put("supportsStartFrame",supportsStartFrame).put("supportsEndFrame",supportsEndFrame)
                .put("supportsStartEndFrame",supportsStartFrame&&supportsEndFrame)
                .put("supportsSemanticKeyframes",supportsSemanticKeyframes).put("supportsStoryboard",supportsStoryboard)
                .put("supportsVideoEdit",supportsVideoEdit).put("supportsAudioEdit",supportsAudioEdit)
                .put("supportsExtension",supportsExtension).put("supportsWhiteModel",supportsWhiteModel)
                .put("maxImageRefs",hardLimits.images()).put("maxVideoRefs",hardLimits.videos())
                .put("maxAudioRefs",hardLimits.audios()).put("maxTotalRefs",hardLimits.total())
                .put("maxReferenceVideoSeconds",maxReferenceVideoSeconds).put("maxReferenceAudioSeconds",maxReferenceAudioSeconds)
                .put("durationMode",durationMode).put("minDuration",minDuration).put("maxDuration",maxDuration).put("durationStep",durationStep);
        n.set("hardLimits",limits(hardLimits));n.set("recommendedLimits",limits(recommendedLimits));
        ArrayNode durations=n.putArray("supportedDurations");supportedDurations.forEach(durations::add);
        ArrayNode ratios=n.putArray("supportedRatios");supportedRatios.forEach(ratios::add);ArrayNode resolutions=n.putArray("supportedResolutions");supportedResolutions.forEach(resolutions::add);ArrayNode formats=n.putArray("supportedOutputFormats");supportedOutputFormats.forEach(formats::add);
        ArrayNode tasks=n.putArray("supportedTaskTypes");supportedTaskTypes.stream().map(Enum::name).sorted().forEach(tasks::add);
        return n;
    }
    public int providerDuration(double requested){
        if(!Double.isFinite(requested)||requested<=0)throw new IllegalArgumentException("DURATION_UNSUPPORTED: 视频时长必须为正数");
        int value=(int)Math.ceil(requested);
        if("RANGE".equals(durationMode)){
            int aligned=minDuration+(int)Math.ceil(Math.max(0,value-minDuration)/(double)Math.max(1,durationStep))*Math.max(1,durationStep);
            if(aligned>maxDuration)throw new IllegalArgumentException("DURATION_UNSUPPORTED: "+requested);
            return aligned;
        }
        return supportedDurations.stream().sorted().filter(duration->duration>=value).findFirst().orElseThrow(()->new IllegalArgumentException("DURATION_UNSUPPORTED: "+requested));
    }
    public void requireResolution(String resolution){if(!supportedResolutions.contains(resolution))throw new IllegalArgumentException("VIDEO_RESOLUTION_UNSUPPORTED_BY_PROFILE: "+resolution);}
    private ObjectNode limits(ReferenceLimits l){return obj().put("images",l.images()).put("videos",l.videos()).put("audios",l.audios()).put("total",l.total());}
}
