package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Seedance20ProviderRuleTest {
    @Test
    void seedance20LoadsOnlyItsOwnProviderRules() {
        var profile = new ProviderCapabilityRegistry().profile("doubao-seedance-2-0-pro");
        var pack = new ProviderRulePackResolver(new RuntimeRulePackLoader())
                .resolve(profile, VideoTaskType.FIRST_FRAME_GENERATE);

        assertThat(pack.namespace()).isEqualTo(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_20);
        assertThat(pack.content()).isNotBlank().contains("Seedance 2.0");
        assertThat(pack.content().length()).isLessThan(6_000);
        assertThat(pack.upstreamCommit()).isEqualTo(RuntimeRulePackLoader.OIUV_COMMIT);
        assertThat(pack.content()).doesNotContain("Seedance 2.5");
    }
}
