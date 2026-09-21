package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class EpisodeFormatResolverTest {
    private final EpisodeFormatResolver resolver = new EpisodeFormatResolver();

    @Test void explicitFormatHasHighestPriority() {
        ObjectNode project = project(180, "GENERAL");
        project.set("episodeFormat", obj().put("profileId", "MICRO_24S").put("family", "MICRO")
                .put("targetDurationSec", 24).put("beatMode", "SINGLE_ROUND"));

        ObjectNode resolved = resolver.resolve(project);

        assertThat(resolved.path("profileId").asText()).isEqualTo("MICRO_24S");
        assertThat(resolved.path("targetDurationSec").asInt()).isEqualTo(24);
    }

    @Test void distributionProfileIsUsedBeforeAutomaticInference() {
        ObjectNode resolved = resolver.resolve(project(180, "HONGGUO_LONG"));

        assertThat(resolved.path("profileId").asText()).isEqualTo("HONGGUO_LONG");
        assertThat(resolved.path("family").asText()).isEqualTo("LONG");
        assertThat(resolved.path("beatMode").asText()).isEqualTo("DOUBLE_ROUND");
        assertThat(resolved.path("midHookPolicy").asText()).isEqualTo("REQUIRED_AT_HALF");
    }

    @Test void general180SecondsIsLongButNeverForcedToHongguo() {
        ObjectNode resolved = resolver.resolve(project(180, "GENERAL"));

        assertThat(resolved.path("profileId").asText()).isEqualTo("GENERAL_LONG");
        assertThat(resolved.path("family").asText()).isEqualTo("LONG");
        assertThat(resolved.path("distributionProfile").asText()).isEqualTo("GENERAL");
        assertThat(resolved.path("sceneLimit").asInt()).isEqualTo(9);
    }

    @Test void hongguoDistributionAppliesOnlyItsOwnSceneAndSeriesPresets(){
        ObjectNode manju=resolver.resolve(project(75,"HONGGUO"));
        ObjectNode standard=resolver.resolve(project(100,"HONGGUO"));
        ObjectNode longForm=resolver.resolve(project(180,"HONGGUO"));
        assertThat(manju.path("profileId").asText()).isEqualTo("HONGGUO_MANJU");
        assertThat(standard.path("profileId").asText()).isEqualTo("HONGGUO_STANDARD");
        assertThat(longForm.path("profileId").asText()).isEqualTo("HONGGUO_LONG");
        assertThat(manju.path("sceneLimit").asInt()).isEqualTo(2);
        assertThat(standard.path("sceneLimit").asInt()).isEqualTo(2);
        assertThat(longForm.path("sceneLimit").asInt()).isEqualTo(3);
        assertThat(standard.path("seriesPresets")).extracting(n->n.asInt()).containsExactly(60,80,100);
        assertThat(longForm.path("seriesPresets")).extracting(n->n.asInt()).containsExactly(30,40,50);
    }

    @Test void resolvesMicroManjuStandardAndCustomFamilies() {
        assertThat(resolver.resolve(project(24, "GENERAL")).path("family").asText()).isEqualTo("MICRO");
        assertThat(resolver.resolve(project(75, "GENERAL")).path("family").asText()).isEqualTo("MANJU");
        assertThat(resolver.resolve(project(100, "GENERAL")).path("family").asText()).isEqualTo("STANDARD");
        assertThat(resolver.resolve(project(150, "OVERSEAS")).path("family").asText()).isEqualTo("CUSTOM");
    }

    @Test void longFormatUsesDenserStoryUnitsThanStandard() {
        int standard = resolver.recommendedEpisodesPerUnit(resolver.resolve(project(100, "GENERAL")));
        int longForm = resolver.recommendedEpisodesPerUnit(resolver.resolve(project(180, "GENERAL")));

        assertThat(longForm).isLessThan(standard);
    }

    private ObjectNode project(int seconds, String distribution) {
        return obj().put("targetDuration", seconds).put("distributionProfile", distribution);
    }
}
