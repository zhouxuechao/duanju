package com.yourapp.drama.provider.audio;

import com.fasterxml.jackson.databind.*;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.model.voice.VoiceGenerator;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.io.InputStream;

public final class SeedAudioVoiceGenerator implements VoiceGenerator {
    private final SeedAudioProperties properties;private final ObjectMapper mapper;private final HttpClient client;
    public SeedAudioVoiceGenerator(SeedAudioProperties properties,ObjectMapper mapper){properties.validate();this.properties=properties;this.mapper=mapper;this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();}
    @Override public VoiceResult generate(VoiceRequest request){
        String speech=Objects.toString(request.spokenText(),"").replaceAll("\\s+"," ").trim();if(speech.isBlank())throw ProviderException.invalid("SPOKEN_TEXT_REQUIRED","TTS 只接受非空 spokenText");if(speech.length()>1200)throw ProviderException.invalid("SPOKEN_TEXT_TOO_LONG","单条对白不能超过 1200 字");
        if(!Double.isFinite(request.speed())||request.speed()<0.5||request.speed()>2)throw ProviderException.invalid("INVALID_SPEED","语速必须为 0.5 至 2.0");
        String referenceAudioData=Objects.toString(request.options().getOrDefault("referenceAudioData",""),"");String voiceId=Objects.toString(request.voiceId(),"").trim();if((voiceId.isBlank()&&referenceAudioData.isBlank())||(!voiceId.isBlank()&&!referenceAudioData.isBlank()))throw ProviderException.invalid("VOICE_REFERENCE_REQUIRED","providerVoiceId 与 referenceAudioData 必须二选一");if(!referenceAudioData.isBlank())try{byte[] decoded=Base64.getDecoder().decode(referenceAudioData);if(decoded.length==0||decoded.length>10_000_000)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw ProviderException.invalid("VOICE_REFERENCE_INVALID","声音参考必须是 10MB 以内的 Base64 音频");}String requestId=UUID.randomUUID().toString();String prompt=Objects.toString(request.prompt(),"").trim();if(prompt.isBlank()||prompt.length()>4_000)throw ProviderException.invalid("VOICE_PROMPT_INVALID","配音请求缺少已编译的 Prompt，或长度超过服务商安全范围");
        Map<String,Object> audio=new LinkedHashMap<>(Map.of("format","mp3","sample_rate",48000,"pitch_rate",0,"speech_rate",Math.round((request.speed()-1)*100),"loudness_rate",0));
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",properties.getModel());body.put("text_prompt",prompt);body.put("audio_config",audio);body.put("watermark",Map.of());
        if(!referenceAudioData.isBlank())body.put("references",List.of(Map.of("audio_data",referenceAudioData)));else body.put("references",List.of(Map.of("speaker",voiceId)));
        try{
            HttpRequest http=HttpRequest.newBuilder(properties.getEndpoint()).timeout(properties.getRequestTimeout()).header("X-Api-Key",properties.getApiKey()).header("X-Api-Request-Id",requestId).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=client.send(http,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requestId=response.headers().firstValue("X-Tt-Logid").orElse(response.headers().firstValue("X-Api-Request-Id").orElse(requestId));
            JsonNode json;try{json=mapper.readTree(response.body());}catch(Exception invalidJson){
                throw new ProviderException("SEED_AUDIO_RESPONSE","配音服务返回非 JSON 内容（HTTP "+response.statusCode()+"），请凭请求ID核对服务商记录",requestId,response.statusCode(),false,response.statusCode()>=500||response.statusCode()<300);
            }
            String providerCode=json.path("code").asText(response.headers().firstValue("X-Api-Status-Code").orElse(""));
            boolean providerFailed=!providerCode.isBlank()&&!Set.of("0","20000000").contains(providerCode);
            if(response.statusCode()<200||response.statusCode()>=300||providerFailed){
                String message=safeMessage(json.path("message").asText(response.headers().firstValue("X-Api-Message").orElse("配音服务未接受请求")));
                throw new ProviderException("SEED_AUDIO_UPSTREAM","配音请求失败（HTTP "+response.statusCode()+(providerCode.isBlank()?"":"，服务码 "+providerCode)+"）："+message,requestId,response.statusCode(),response.statusCode()==429,response.statusCode()>=500);
            }
            String encoded=json.path("audio").asText("");String url=json.path("url").asText("");byte[] content=encoded.isBlank()?download(url):Base64.getDecoder().decode(encoded);
            if(content.length<44)throw new ProviderException("EMPTY_AUDIO","配音服务没有返回可用音频",requestId,200,false,true);
            double duration=json.path("duration").asDouble(0);
            if(!Double.isFinite(duration)||duration<=0)throw new ProviderException("AUDIO_DURATION_MISSING","配音服务未返回处理后的音频时长，请核对服务商记录；不会用台词字数猜测时长",requestId,200,false,true);
            return new VoiceResult("VOLCENGINE",properties.getModel(),requestId,url.isBlank()?null:url,content,"audio/mpeg",duration,false);
        }catch(ProviderException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ProviderException("SEED_AUDIO_INTERRUPTED","配音请求中断，请核对服务商记录",requestId,0,false,true);}catch(Exception e){throw new ProviderException("SEED_AUDIO_NETWORK","配音请求未完成，请核对服务商记录",requestId,0,false,true);}
    }
    private String safeMessage(String value){String result=value.replace(properties.getApiKey(),"[redacted]").replaceAll("[\\r\\n\\t]+"," ");return result.substring(0,Math.min(result.length(),300));}
    private byte[] download(String value)throws Exception{if(value==null||value.isBlank())return new byte[0];URI uri=URI.create(value);String host=uri.getHost();if(!"https".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalArgumentException("音频结果地址无效");
        for(InetAddress address:InetAddress.getAllByName(host))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress())throw new IllegalArgumentException("音频结果不能指向内部网络");
        HttpResponse<InputStream> result=client.send(HttpRequest.newBuilder(uri).timeout(properties.getRequestTimeout()).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream input=result.body()){if(result.statusCode()!=200)throw new IllegalStateException("音频结果下载失败");byte[] bytes=input.readNBytes(32_000_001);if(bytes.length>32_000_000)throw new IllegalArgumentException("音频结果过大");return bytes;}}
}
