package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Seedance25RulePackLoaderTest {
    @Test void loadsOnlySelectedCompactRuntimeRulesAndKeepsUpstreamTraceability() {
        RuntimeRulePackLoader loader = new RuntimeRulePackLoader();

        RuntimeRulePackLoader.RulePack pack = loader.load(
                RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_25,
                List.of("material-authority", "locked-routing"));

        assertThat(pack.ruleIds()).containsExactly("material-authority", "locked-routing");
        assertThat(pack.content()).contains("REFERENCE_AUTHORITY", "LOCKED");
        assertThat(pack.content()).doesNotContain("parameter-separation", "storyboard-grid");
        assertThat(pack.upstreamCommit()).isEqualTo("4f318097c54a2e24ea34d3c9d23d30f5ec332f11");
        assertThat(pack.fingerprint()).hasSize(64);
        assertThat(pack.content().length()).isLessThan(8_000);
        assertThat(pack.rules()).extracting(RuntimeRulePackLoader.RuleFragment::ruleId).containsExactly("material-authority", "locked-routing");
        assertThat(pack.rules()).allSatisfy(rule -> {
            assertThat(rule.content()).isNotBlank();
            assertThat(rule.contentHash()).hasSize(64);
            assertThat(rule.source()).startsWith("/provider-rules/seedance-2.5/");
            assertThat(rule.upstreamCommit()).isEqualTo(RuntimeRulePackLoader.SEEDANCE_25_UPSTREAM_COMMIT);
            assertThat(rule.priority()).isPositive();
        });
    }

    @Test void providerResolverSelectsRulesFromTaskAndProfileInsteadOfLoadingWholeSkill() {
        VideoModelProfile profile = new ProviderCapabilityRegistry().profile("doubao-seedance-2-5-260628");
        RuntimeRulePackLoader.RulePack pack = new ProviderRulePackResolver(new RuntimeRulePackLoader())
                .resolve(profile, VideoTaskType.FIRST_LAST_FRAME_GENERATE);

        assertThat(pack.ruleIds()).contains("material-authority", "parameter-separation", "spatial-keyframes", "locked-routing");
        assertThat(pack.content()).doesNotContain("完整上游技能");
    }
    @Test void seedance20LoadsItsOwnRulesAndDoesNotLoadSeedance25SpecificRules() {
        VideoModelProfile profile = new ProviderCapabilityRegistry().profile("doubao-seedance-2-0");
        RuntimeRulePackLoader.RulePack pack = new ProviderRulePackResolver(new RuntimeRulePackLoader())
                .resolve(profile, VideoTaskType.FIRST_FRAME_GENERATE);

        assertThat(pack.namespace()).isEqualTo(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_20);
        assertThat(pack.ruleIds()).containsExactly("sd2-pe");
        assertThat(pack.content()).contains("Seedance 2.0").doesNotContain("Seedance 2.5");
        assertThat(pack.upstreamCommit()).isEqualTo(RuntimeRulePackLoader.OIUV_COMMIT);
    }
    @Test void sameLoaderInfrastructureSupportsScreenwritingAndDirectorNamespaces() {
        RuntimeRulePackLoader loader=new RuntimeRulePackLoader();
        assertThat(loader.load(RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE,List.of("character-bible")).content()).isNotBlank();
        assertThat(loader.load(RuntimeRulePackLoader.Namespace.DIRECTOR,List.of("cinematic-dramaturgy")).content()).isNotBlank();
    }
}
