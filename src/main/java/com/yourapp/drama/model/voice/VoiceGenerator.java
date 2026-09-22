package com.yourapp.drama.model.voice;

import java.util.Map;

/** Speech providers consume spokenText only. Subtitle generation consumes subtitleText separately. */
public interface VoiceGenerator {
    VoiceResult generate(VoiceRequest request);
    record VoiceRequest(String dialogueId,String spokenText,String voiceId,String dialect,double speed,String prompt,Map<String,Object> options){}
    record VoiceResult(String provider,String model,String requestId,String providerUrl,byte[] content,String contentType,double durationSeconds,boolean simulated){
        public VoiceResult { content=content==null?new byte[0]:content.clone(); }
        @Override public byte[] content(){return content.clone();}
    }
}
