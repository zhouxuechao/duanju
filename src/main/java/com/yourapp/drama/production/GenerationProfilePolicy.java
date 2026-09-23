package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public final class GenerationProfilePolicy {
    private final ProviderCapabilityRegistry capabilities;
    public GenerationProfilePolicy(ProviderCapabilityRegistry capabilities){this.capabilities=capabilities;}

    public ObjectNode applyDefaults(ObjectNode source){
        ObjectNode value=source.deepCopy();GenerationProfile profile=parse(value.path("generationProfile").asText("TEST"));value.put("generationProfile",profile.name());
        if(profile==GenerationProfile.FINAL){required(value,"imageModel");required(value,"videoModel");required(value,"videoResolution");required(value,"imageSize");}
        else{
            value.putIfAbsent("imageModel",text(capabilities.configuredImageModel()));value.putIfAbsent("videoModel",text(capabilities.configuredVideoModel()));value.putIfAbsent("imageSize",text(capabilities.defaultImageSize()));
            value.putIfAbsent("videoResolution",text(profile==GenerationProfile.STANDARD?"720p":capabilities.defaultVideoResolution()));
        }
        if(profile!=GenerationProfile.FINAL&&!"2K".equals(value.path("imageSize").asText()))throw new IllegalArgumentException("TEST / STANDARD 的 Seedream 5.0 图片尺寸固定为 2K");
        capabilities.profile(value.path("videoModel").asText()).requireResolution(value.path("videoResolution").asText());
        return value;
    }
    public ObjectNode resolved(ObjectNode project){return applyDefaults(project);}
    private GenerationProfile parse(String raw){try{return GenerationProfile.valueOf(raw.toUpperCase(Locale.ROOT));}catch(Exception e){throw new IllegalArgumentException("generationProfile 只支持 TEST、STANDARD、FINAL");}}
    private void required(ObjectNode value,String field){if(value.path(field).asText("").isBlank())throw new IllegalArgumentException("FINAL 必须显式配置 "+field);}
    private com.fasterxml.jackson.databind.node.TextNode text(String value){return com.fasterxml.jackson.databind.node.TextNode.valueOf(value);}
}
