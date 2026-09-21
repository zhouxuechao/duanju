package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.provider.mock.*;
import com.yourapp.drama.provider.volcengine.*;
import com.yourapp.drama.production.*;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VolcengineProperties.class)
public class ProviderConfiguration {
    @Bean
    VisualQualityProtocol visualQualityProtocol(){return new VisualQualityProtocol();}
    @Bean @ConditionalOnProperty(name="drama.quality.visual-reviewer",havingValue="fake",matchIfMissing=true)
    VisualQualityReviewer fakeVisualQualityReviewer(ObjectMapper mapper){return new FakeVisualQualityReviewer(mapper);}
    @Bean @ConditionalOnProperty(name="drama.quality.visual-reviewer",havingValue="fake",matchIfMissing=true)
    VideoQualityReviewer fakeVideoQualityReviewer(ObjectMapper mapper){return new FakeVideoQualityReviewer(mapper);}
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="mock", matchIfMissing=true)
    LlmGateway mockLlm(ObjectMapper mapper) { return new MockLlmGateway(mapper); }
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="mock", matchIfMissing=true)
    ImageGenerator mockImage() { return new MockImageGenerator(); }
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="mock", matchIfMissing=true)
    VideoGenerator mockVideo() { return new MockVideoGenerator(); }

    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="volcengine")
    ArkHttpClient arkHttpClient(ObjectMapper mapper, VolcengineProperties properties) { return new ArkHttpClient(mapper, properties); }
    @Bean
    StructuredJson structuredJson(ObjectMapper mapper, Validator validator) { return new StructuredJson(mapper, validator); }
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="volcengine")
    LlmGateway volcengineLlm(ArkHttpClient client, VolcengineProperties p, StructuredJson schema) { return new VolcengineLlmGateway(client, p, schema); }
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="volcengine")
    ImageGenerator volcengineImage(ArkHttpClient client, VolcengineProperties p) { return new VolcengineImageGenerator(client, p); }
    @Bean @ConditionalOnProperty(name="drama.provider.mode", havingValue="volcengine")
    VideoGenerator volcengineVideo(ArkHttpClient client, VolcengineProperties p,ProviderCapabilityRegistry capabilities) { return new VolcengineVideoGenerator(client, p,capabilities); }
    @Bean @ConditionalOnProperty(name="drama.quality.visual-reviewer",havingValue="volcengine")
    VisualQualityReviewer volcengineVisualQualityReviewer(ArkHttpClient client,VolcengineProperties p,StructuredJson schema,ObjectMapper mapper,VisualQualityProtocol protocol){
        return new VolcengineVisualQualityReviewer(client,p,schema,mapper,protocol);
    }
    @Bean @ConditionalOnProperty(name="drama.quality.visual-reviewer",havingValue="volcengine")
    VideoQualityReviewer volcengineVideoQualityReviewer(ArkHttpClient client,VolcengineProperties p,StructuredJson schema,VisualQualityProtocol protocol){
        return new VolcengineVideoQualityReviewer(client,p,schema,protocol);
    }
}
