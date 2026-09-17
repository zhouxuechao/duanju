package com.yourapp.drama.provider;

import com.yourapp.drama.production.VisualQualityReviewer;
import com.yourapp.drama.provider.volcengine.VolcengineVisualQualityReviewer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={
    "drama.jobs.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:visual-reviewer-config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "drama.provider.mode=volcengine",
    "drama.quality.visual-reviewer=volcengine",
    "drama.provider.volcengine.api-key=test-key",
    "drama.provider.volcengine.text-model=test-text",
    "drama.provider.volcengine.image-model=test-image",
    "drama.provider.volcengine.video-model=test-video",
    "drama.provider.volcengine.vlm-model=test-vlm"
    ,"drama.provider.seed-audio.api-key=test-audio-key"
})
class VisualQualityReviewerConfigurationTest {
    @Autowired VisualQualityReviewer reviewer;

    @Test void volcengineModeSelectsTheRealVisualReviewer(){
        assertThat(reviewer).isInstanceOf(VolcengineVisualQualityReviewer.class);
    }
}
