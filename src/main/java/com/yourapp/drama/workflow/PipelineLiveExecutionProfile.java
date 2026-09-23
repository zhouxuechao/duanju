package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.production.ProviderCapabilityRegistry;

import static com.yourapp.drama.workflow.Documents.obj;

/** Fixed low-cost contract for the first production-backed Phase B canary. */
public record PipelineLiveExecutionProfile(
        String generationProfile,String assetDependency,
        int assetImageRequests,int keyframeRequests,int storyLlmRequests,int directorLlmRequests,
        int videoMax,int ttsMax,int vlmMax,int lipsyncMax,
        int videoDurationSeconds,String videoResolution,String imageSize,String aspectRatioIntent) {
    public static PipelineLiveExecutionProfile phaseB(){return new PipelineLiveExecutionProfile(
            "TEST","A1",4,4,6,5,4,2,8,0,5,"480p","2K","9:16");}
    public int imageMax(){return assetImageRequests+keyframeRequests;}
    public int llmMax(){return storyLlmRequests+directorLlmRequests;}
    public String imageModel(){return ProviderCapabilityRegistry.SEEDREAM_50;}
    public String videoModel(){return ProviderCapabilityRegistry.SEEDANCE_20_FAST;}
    public String requireVoiceId(String value){
        String voice=value==null?"":value.trim();
        if(voice.isBlank()||voice.toLowerCase(java.util.Locale.ROOT).startsWith("mock-"))
            throw new WorkflowException("PIPELINE_CANARY_TTS_VOICE_INVALID","Phase B Pipeline Canary 必须配置真实 CANARY_TTS_VOICE_ID，禁止 mock 音色");
        return voice;
    }
    public ObjectNode toJson(){
        ObjectNode value=obj().put("generationProfile",generationProfile).put("assetDependency",assetDependency)
                .put("imageModel",imageModel()).put("imageSize",imageSize).put("aspectRatioIntent",aspectRatioIntent)
                .put("videoModel",videoModel()).put("videoResolution",videoResolution).put("videoDurationSeconds",videoDurationSeconds);
        value.set("requests",obj().put("assetImage",assetImageRequests).put("keyframe",keyframeRequests).put("image",imageMax())
                .put("storyLlm",storyLlmRequests).put("directorLlm",directorLlmRequests).put("storyDirectorLlm",llmMax())
                .put("video",videoMax).put("audio",ttsMax).put("vlmQc",vlmMax).put("lipsync",lipsyncMax));
        return value;
    }
}
