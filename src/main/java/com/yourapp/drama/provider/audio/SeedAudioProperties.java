package com.yourapp.drama.provider.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("drama.provider.seed-audio")
public class SeedAudioProperties {
    private URI endpoint=URI.create("https://openspeech.bytedance.com/api/v3/tts/create");
    private String apiKey=""; private String model="seed-audio-1.0"; private Duration requestTimeout=Duration.ofMinutes(5);
    public void validate(){String host=endpoint==null?null:endpoint.getHost();if(host==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||
        !("https".equals(endpoint.getScheme())||("http".equals(endpoint.getScheme())&&Set.of("127.0.0.1","localhost").contains(host))))throw new IllegalStateException("Seed Audio 地址必须为 HTTPS（本地测试可用 HTTP）");
        if(apiKey==null||apiKey.isBlank())throw new IllegalStateException("真实模式需要 SEED_AUDIO_API_KEY");if(model==null||model.isBlank())throw new IllegalStateException("需要配置 Seed Audio 模型");}
    public URI getEndpoint(){return endpoint;} public void setEndpoint(URI v){endpoint=v;} public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
    public String getModel(){return model;} public void setModel(String v){model=v;} public Duration getRequestTimeout(){return requestTimeout;} public void setRequestTimeout(Duration v){requestTimeout=v;}
}
