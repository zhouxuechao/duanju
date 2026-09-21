package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class ProviderRulePackResolver {
    private final RuntimeRulePackLoader loader;
    private final ProviderRulePackProjector projector=new ProviderRulePackProjector();
    public ProviderRulePackResolver(RuntimeRulePackLoader loader){this.loader=loader;}
    public RuntimeRulePackLoader.RulePack resolve(VideoModelProfile profile,VideoTaskType taskType){
        if("SEEDANCE_2_0".equals(profile.family())){
            RuntimeRulePackLoader.RulePack raw=loader.load(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_20,List.of("sd2-pe"));
            String content=projector.project(raw.content(),taskType,5_000);
            String fingerprint=sha256(raw.fingerprint()+"|"+sha256(content)+"|"+profile.capabilityFingerprint()+"|"+taskType+"|"+PromptCompiler.VIDEO_COMPILER_VERSION);
            return new RuntimeRulePackLoader.RulePack(raw.namespace(),raw.ruleIds(),content,raw.upstreamCommit(),fingerprint,raw.rules());
        }
        if(!"SEEDANCE_2_5".equals(profile.family())){
            String fingerprint=sha256("PROVIDER_GENERIC|"+profile.capabilityFingerprint()+"|"+taskType+"|"+PromptCompiler.VIDEO_COMPILER_VERSION);
            return new RuntimeRulePackLoader.RulePack(RuntimeRulePackLoader.Namespace.PROVIDER_GENERIC,List.of(),"","LOCAL",fingerprint);
        }
        List<String> ids=new ArrayList<>(List.of("material-authority","parameter-separation"));
        if(profile.supportsSemanticKeyframes()||taskType==VideoTaskType.FIRST_LAST_FRAME_GENERATE)ids.add("spatial-keyframes");
        if(taskType==VideoTaskType.STORYBOARD_GUIDED)ids.add("storyboard-grid");
        ids.add(taskType.lockMode()==ProviderTaskLockMode.LOCKED?"locked-routing":"unlocked-routing");
        RuntimeRulePackLoader.RulePack raw=loader.load(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_25,ids);
        String content=projector.project(raw.content(),taskType,5_000);
        String fingerprint=sha256(raw.fingerprint()+"|"+sha256(content)+"|"+profile.capabilityFingerprint()+"|"+taskType+"|"+PromptCompiler.VIDEO_COMPILER_VERSION);
        return new RuntimeRulePackLoader.RulePack(raw.namespace(),raw.ruleIds(),content,raw.upstreamCommit(),fingerprint,raw.rules());
    }
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
