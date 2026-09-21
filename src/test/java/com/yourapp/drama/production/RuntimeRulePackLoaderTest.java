package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRulePackLoaderTest {
    @Test
    void exposesOnlyCanonicalScreenwritingNamespace() {
        assertThat(java.util.Arrays.stream(RuntimeRulePackLoader.Namespace.values()).map(Enum::name))
                .doesNotContain("SCREENWRITING");
    }
    @Test
    void loadsRealVendoredMarkdownWithCompleteProvenance() {
        RuntimeRulePackLoader.RulePack pack = new RuntimeRulePackLoader().load(
                RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE,
                List.of("character-bible"));

        assertThat(pack.content()).contains("人物", "语言");
        assertThat(pack.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("character-bible");
            assertThat(rule.namespace()).isEqualTo(RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE);
            assertThat(rule.content()).isNotBlank();
            assertThat(rule.contentHash()).hasSize(64);
            assertThat(rule.sourceRepo()).isEqualTo("lixiaoxiao9888-create/short-drama-factory");
            assertThat(rule.upstreamCommit()).isEqualTo(RuntimeRulePackLoader.SHORT_DRAMA_FACTORY_COMMIT);
            assertThat(rule.sourcePath()).isEqualTo("references/character-bible.md");
        });
    }

    @Test
    void directorAndSpecializedPacksUseTheirOwnFixedSnapshots() {
        RuntimeRulePackLoader loader = new RuntimeRulePackLoader();
        var director = loader.load(RuntimeRulePackLoader.Namespace.SPATIAL, List.of("spatial-reference-system-v3"));
        var brief = loader.load(RuntimeRulePackLoader.Namespace.STORY_PATTERN, List.of("script-brief"));

        assertThat(director.rules().getFirst().sourceRepo()).isEqualTo("lixiaoxiao9888-create/manju-laoli-skill");
        assertThat(director.rules().getFirst().upstreamCommit()).isEqualTo(RuntimeRulePackLoader.MANJU_LAOLI_COMMIT);
        assertThat(brief.rules().getFirst().sourceRepo()).isEqualTo("oiuv/ai-short-drama");
        assertThat(brief.rules().getFirst().upstreamCommit()).isEqualTo(RuntimeRulePackLoader.OIUV_COMMIT);
    }
}
