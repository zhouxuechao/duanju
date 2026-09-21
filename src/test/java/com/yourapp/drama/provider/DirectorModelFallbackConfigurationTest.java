package com.yourapp.drama.provider;

import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "ARK_TEXT_MODEL=deepseek-writer",
    "ARK_TEXT_API_STYLE=chat",
    "ARK_VLM_MODEL=vision-review"
})
@ActiveProfiles("test")
class DirectorModelFallbackConfigurationTest {
    @Autowired VolcengineProperties properties;

    @Test void directorDefaultsToTheTextModelAndItsMatchingApiStyle() {
        assertThat(properties.getDirectorModel()).isEqualTo("deepseek-writer");
        assertThat(properties.getDirectorApiStyle()).isEqualTo("chat");
    }
}
