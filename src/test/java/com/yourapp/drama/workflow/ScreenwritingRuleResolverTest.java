package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class ScreenwritingRuleResolverTest {
    private final ScreenwritingRuleResolver resolver = new ScreenwritingRuleResolver();

    @Test void resolvesBaseTypeFormatAndDistributionWithoutGenreIfElse() {
        ObjectNode profile = obj().put("storyType", "SUSPENSE").put("settingGenre", "RURAL_HORROR");
        profile.putArray("tropes").add("HIDDEN_IDENTITY").add("ISOLATED_COMMUNITY");
        ObjectNode format = new EpisodeFormatResolver().resolve(obj().put("targetDuration", 180)
                .put("distributionProfile", "HONGGUO_LONG"));

        ObjectNode pack = resolver.resolve("SCRIPT", profile, format, "HONGGUO_LONG");

        assertThat(pack.path("rules").findValuesAsText("ruleId"))
                .contains("BASE_SCRIPT", "TYPE_SUSPENSE_MYSTERY", "FORMAT_LONG", "DIST_HONGGUO_LONG");
        assertThat(pack.path("fingerprint").asText()).hasSize(64);
        assertThat(pack.path("storyTypeRule").path("coreLoop").asText()).contains("线索", "真相");
        assertThat(pack.path("upstreamSources").findValuesAsText("path"))
                .contains("references/dialogue-doctor-anti-ai.md", "templates/episode-format.md", "references/hongguo-beat-sheet.md");
    }

    @Test void generalDistributionDoesNotLeakHongguoRules() {
        ObjectNode profile = obj().put("storyType", "GROWTH").put("settingGenre", "URBAN");
        profile.putArray("tropes");
        ObjectNode format = new EpisodeFormatResolver().resolve(obj().put("targetDuration", 180)
                .put("distributionProfile", "GENERAL"));

        ObjectNode pack = resolver.resolve("OUTLINE", profile, format, "GENERAL");

        assertThat(pack.path("rules").findValuesAsText("ruleId"))
                .contains("BASE_OUTLINE", "TYPE_GROWTH", "FORMAT_LONG")
                .doesNotContain("DIST_HONGGUO_LONG");
        assertThat(pack.path("upstreamSources").findValuesAsText("path"))
                .doesNotContain("references/hongguo-beat-sheet.md");
    }

    @Test void everyHongguoFormatUsesTheDistributionSpecificSourcePack(){
        ObjectNode profile=obj().put("storyType","REVENGE");profile.putArray("tropes");
        ObjectNode format=new EpisodeFormatResolver().resolve(obj().put("targetDuration",100).put("distributionProfile","HONGGUO"));
        ObjectNode pack=resolver.resolve("SCRIPT",profile,format,"HONGGUO_STANDARD");
        assertThat(pack.path("upstreamSources").findValuesAsText("path")).contains("references/hongguo-beat-sheet.md");
    }
}
