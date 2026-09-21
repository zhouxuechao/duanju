package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ModelSwitchTest {
 @Test void switchingModelFamilyChangesCapabilitiesRulesAndFingerprint(){var registry=new ProviderCapabilityRegistry();var resolver=new ProviderRulePackResolver(new RuntimeRulePackLoader());var v20=resolver.resolve(registry.profile("doubao-seedance-2-0-pro"),VideoTaskType.FIRST_FRAME_GENERATE);var v25=resolver.resolve(registry.profile("doubao-seedance-2-5-260628"),VideoTaskType.FIRST_FRAME_GENERATE);assertThat(v20.namespace()).isNotEqualTo(v25.namespace());assertThat(v20.fingerprint()).isNotEqualTo(v25.fingerprint());assertThat(v20.ruleIds()).isNotEqualTo(v25.ruleIds());}
}
