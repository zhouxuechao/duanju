package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import static com.yourapp.drama.workflow.Documents.obj;

/** Capabilities are restricted to parameters implemented by the current adapters. */
@Component
public class ProviderCapabilityRegistry {
    public static final String VERSION="volcengine-model-profile-2026-09-21";
    private final String configuredVideoModel;
    public ProviderCapabilityRegistry(){this("doubao-seedance-2-0");}
    @Autowired public ProviderCapabilityRegistry(@Value("${drama.provider.volcengine.video-model:doubao-seedance-2-0}") String configuredVideoModel){this.configuredVideoModel=configuredVideoModel;}
    public ObjectNode image(){ObjectNode value=obj().put("version",VERSION).put("source","ADAPTER_ALLOWLIST").put("supportsMultipleImages",true).put("supportsRegionEdit",false).put("maxImageRefs",10);value.putArray("supportedRatios").add("9:16").add("16:9").add("1:1");return value;}
    public ObjectNode video(){return profile(configuredVideoModel).toJson();}
    public ObjectNode video(String modelId){return profile(modelId).toJson();}
    public VideoModelProfile profile(String modelId){
        String fallback=configuredVideoModel==null||configuredVideoModel.isBlank()?"doubao-seedance-2-0":configuredVideoModel.trim();
        String id=modelId==null||modelId.isBlank()?fallback:modelId.trim();
        boolean seedance25=id.toLowerCase().contains("seedance-2-5");
        var hard=seedance25?new VideoModelProfile.ReferenceLimits(30,10,10,50):new VideoModelProfile.ReferenceLimits(1,1,1,3);
        var recommended=seedance25?new VideoModelProfile.ReferenceLimits(8,5,5,18):new VideoModelProfile.ReferenceLimits(1,1,1,2);
        Set<VideoTaskType> tasks=seedance25?Set.of(VideoTaskType.REFERENCE_GENERATE,VideoTaskType.FIRST_FRAME_GENERATE,VideoTaskType.FIRST_LAST_FRAME_GENERATE,VideoTaskType.KEYFRAME_GENERATE,VideoTaskType.STORYBOARD_GUIDED):Set.of(VideoTaskType.REFERENCE_GENERATE,VideoTaskType.FIRST_FRAME_GENERATE,VideoTaskType.FIRST_LAST_FRAME_GENERATE);
        String family=seedance25?"SEEDANCE_2_5":"SEEDANCE_2_0";
        String version=seedance25?"seedance-2.5-profile-v1":"seedance-2.0-profile-v1";
        double maxVideoSeconds=30,maxAudioSeconds=30;List<String> outputFormats=List.of("mp4");
        String fingerprint=sha256(id+"|"+version+"|"+hard+"|"+recommended+"|"+maxVideoSeconds+"|"+maxAudioSeconds+"|"+outputFormats+"|"+tasks);
        return new VideoModelProfile(id,family,version,fingerprint,true,true,true,true,true,seedance25,seedance25,seedance25,seedance25,seedance25,seedance25,hard,recommended,
                maxVideoSeconds,maxAudioSeconds,seedance25?List.of(5,10,15,30):List.of(4,8,12),seedance25?List.of("adaptive","16:9","9:16","1:1","4:3","3:4","21:9"):List.of("16:9","9:16","1:1"),List.of("480p","720p","1080p"),outputFormats,tasks);
    }
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
