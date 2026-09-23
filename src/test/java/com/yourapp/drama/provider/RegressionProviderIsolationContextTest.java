package com.yourapp.drama.provider;

import com.yourapp.drama.production.FakeVideoQualityReviewer;
import com.yourapp.drama.production.FakeVisualQualityReviewer;
import com.yourapp.drama.production.VideoQualityReviewer;
import com.yourapp.drama.production.VisualQualityReviewer;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RegressionProviderIsolationContextTest {
    @Autowired ApplicationContext context;
    @Autowired VisualQualityReviewer visualReviewer;
    @Autowired VideoQualityReviewer videoReviewer;

    @Test void testProfileUsesMockProviderAndFakeReviewersWithoutArkClient(){
        assertThat(visualReviewer).isInstanceOf(FakeVisualQualityReviewer.class);
        assertThat(videoReviewer).isInstanceOf(FakeVideoQualityReviewer.class);
        assertThat(context.getBeansOfType(ArkHttpClient.class)).isEmpty();
    }
}
