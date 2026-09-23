package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.yourapp.drama.workflow.Documents.obj;

/** Capabilities are restricted to parameters implemented by the current adapters. */
@Component
public class ProviderCapabilityRegistry {
    public static final String VERSION="volcengine-model-profile-2026-09-23";
    public static final String SEEDANCE_20="doubao-seedance-2-0";
    public static final String SEEDANCE_25="doubao-seedance-2-5-260628";
    public static final String SEEDANCE_20_FAST="doubao-seedance-2-0-fast-260128";
    public static final String SEEDREAM_50="doubao-seedream-5-0-260128";
    private final String configuredVideoModel;
    private final String configuredImageModel;
    private final String defaultVideoResolution;
    private final String defaultImageSize;
    private final Map<String,VideoModelProfile> verifiedProfiles;
    private final Map<String,ImageModelProfile> verifiedImageProfiles;
    public ProviderCapabilityRegistry(){this(SEEDANCE_20_FAST,SEEDREAM_50,"480p","2K",4,15,1);}
    public ProviderCapabilityRegistry(String configuredVideoModel){this(configuredVideoModel,SEEDREAM_50,"480p","2K",4,15,1);}
    @Autowired public ProviderCapabilityRegistry(@Value("${drama.provider.volcengine.video-model:doubao-seedance-2-0-fast-260128}") String configuredVideoModel,
        @Value("${drama.provider.volcengine.image-model:doubao-seedream-5-0-260128}") String configuredImageModel,
        @Value("${drama.provider.volcengine.video-resolution:480p}") String defaultVideoResolution,
        @Value("${drama.provider.volcengine.image-size:2K}") String defaultImageSize,
        @Value("${drama.provider.volcengine.video-min-duration:4}") int minDuration,
        @Value("${drama.provider.volcengine.video-max-duration:15}") int maxDuration,
        @Value("${drama.provider.volcengine.video-duration-step:1}") int durationStep){
        this.configuredVideoModel=blank(configuredVideoModel,SEEDANCE_20_FAST);this.configuredImageModel=blank(configuredImageModel,SEEDREAM_50);this.defaultVideoResolution=blank(defaultVideoResolution,"480p");this.defaultImageSize=blank(defaultImageSize,"2K");
        this.verifiedProfiles=Map.of(SEEDANCE_20,buildLegacy(SEEDANCE_20,false),SEEDANCE_25,buildLegacy(SEEDANCE_25,true),SEEDANCE_20_FAST,buildFast(minDuration,maxDuration,durationStep));
        this.verifiedImageProfiles=Map.of(SEEDREAM_50,buildSeedream50());
    }
    public ObjectNode image(){return imageProfile(configuredImageModel).toJson();}
    public ObjectNode image(String modelId){return imageProfile(modelId).toJson();}
    public ObjectNode video(){return profile(configuredVideoModel).toJson();}
    public ObjectNode video(String modelId){return profile(modelId).toJson();}
    public VideoModelProfile profile(String modelId){
        String fallback=configuredVideoModel;
        String id=modelId==null||modelId.isBlank()?fallback:modelId.trim();
        VideoModelProfile profile=verifiedProfiles.get(id);
        if(profile==null)throw new IllegalArgumentException("UNVERIFIED_PROVIDER_MODEL: no explicit capability profile for "+id);
        return profile;
    }
    public ImageModelProfile imageProfile(String modelId){
        String id=modelId==null||modelId.isBlank()?configuredImageModel:modelId.trim();ImageModelProfile profile=verifiedImageProfiles.get(id);
        if(profile==null)throw new IllegalArgumentException("UNVERIFIED_PROVIDER_MODEL: no explicit capability profile for "+id);
        return profile;
    }
    public String configuredVideoModel(){return configuredVideoModel;}
    public String configuredImageModel(){return configuredImageModel;}
    public String defaultVideoResolution(){return defaultVideoResolution;}
    public String defaultImageSize(){return defaultImageSize;}
    private VideoModelProfile buildLegacy(String id,boolean seedance25){
        var hard=seedance25?new VideoModelProfile.ReferenceLimits(30,10,10,50):new VideoModelProfile.ReferenceLimits(1,1,1,3);
        var recommended=seedance25?new VideoModelProfile.ReferenceLimits(8,5,5,18):new VideoModelProfile.ReferenceLimits(1,1,1,2);
        Set<VideoTaskType> tasks=seedance25?Set.of(VideoTaskType.REFERENCE_GENERATE,VideoTaskType.FIRST_FRAME_GENERATE,VideoTaskType.FIRST_LAST_FRAME_GENERATE,VideoTaskType.KEYFRAME_GENERATE,VideoTaskType.STORYBOARD_GUIDED):Set.of(VideoTaskType.REFERENCE_GENERATE,VideoTaskType.FIRST_FRAME_GENERATE,VideoTaskType.FIRST_LAST_FRAME_GENERATE);
        String family=seedance25?"SEEDANCE_2_5":"SEEDANCE_2_0";
        String version=seedance25?"seedance-2.5-profile-v1":"seedance-2.0-profile-v1";
        double maxVideoSeconds=30,maxAudioSeconds=30;List<String> outputFormats=List.of("mp4");
        String taskFingerprint=tasks.stream().map(Enum::name).sorted().reduce((a,b)->a+","+b).orElse("");
        String fingerprint=sha256(id+"|"+version+"|"+hard+"|"+recommended+"|"+maxVideoSeconds+"|"+maxAudioSeconds+"|"+outputFormats+"|"+taskFingerprint);
        return new VideoModelProfile(id,family,version,fingerprint,true,true,true,true,true,seedance25,seedance25,seedance25,seedance25,seedance25,seedance25,hard,recommended,
                maxVideoSeconds,maxAudioSeconds,"ENUM",0,0,0,seedance25?List.of(5,10,15,30):List.of(4,8,12),seedance25?List.of("adaptive","16:9","9:16","1:1","4:3","3:4","21:9"):List.of("16:9","9:16","1:1"),List.of("480p","720p","1080p"),outputFormats,tasks,List.of());
    }
    private VideoModelProfile buildFast(int min,int max,int step){
        if(min<1||max<min||step<1)throw new IllegalArgumentException("Fast 模型时长范围配置无效");
        var hard=new VideoModelProfile.ReferenceLimits(30,10,10,50);var recommended=new VideoModelProfile.ReferenceLimits(8,5,5,18);
        Set<VideoTaskType> tasks=Set.of(VideoTaskType.REFERENCE_GENERATE,VideoTaskType.FIRST_FRAME_GENERATE,VideoTaskType.FIRST_LAST_FRAME_GENERATE,VideoTaskType.KEYFRAME_GENERATE,VideoTaskType.STORYBOARD_GUIDED);
        var outputs=List.of(new VideoOutputProfile("480p","9:16",496,864,0,"LIVE_OBSERVED"));
        String version="seedance-2.0-fast-profile-v1",fingerprint=sha256(SEEDANCE_20_FAST+"|"+version+"|"+min+"|"+max+"|"+step+"|480p,720p|"+tasks+"|"+outputs);
        return new VideoModelProfile(SEEDANCE_20_FAST,"SEEDANCE_2_0_FAST",version,fingerprint,true,true,true,true,true,true,true,true,true,true,true,hard,recommended,30,30,"RANGE",min,max,step,List.of(),List.of("adaptive","16:9","9:16","1:1","4:3","3:4","21:9"),List.of("480p","720p"),List.of("mp4"),tasks,outputs);
    }
    private ImageModelProfile buildSeedream50(){
        String profileVersion="seedream-5.0-profile-v1",verification="STATIC_UNVERIFIED";List<String> sizes=List.of("2K"),ratios=List.of("9:16","16:9","1:1");
        List<ImageOutputProfile> outputs=ratios.stream().map(ratio->new ImageOutputProfile("2K",ratio,"2K",false,verification)).toList();
        String fingerprint=sha256(SEEDREAM_50+"|"+profileVersion+"|"+sizes+"|"+ratios+"|"+outputs+"|10|true|true|false|false");
        return new ImageModelProfile(SEEDREAM_50,"SEEDREAM_5_0",profileVersion,fingerprint,verification,sizes,"2K",ratios,outputs,true,true,10,false,true,false);
    }
    private static String blank(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
