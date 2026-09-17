package com.yourapp.drama.provider.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.voice.VoiceGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(SeedAudioProperties.class)
public class VoiceConfiguration {
 @Bean @ConditionalOnProperty(name="drama.provider.mode",havingValue="mock",matchIfMissing=true) VoiceGenerator mockVoice(){return new MockVoiceGenerator();}
 @Bean @ConditionalOnProperty(name="drama.provider.mode",havingValue="volcengine") VoiceGenerator seedAudio(SeedAudioProperties p,ObjectMapper mapper){return new SeedAudioVoiceGenerator(p,mapper);}
}
