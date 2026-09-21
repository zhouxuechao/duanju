package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class Seedance25ProviderRuleTest {
 @Test void selectsTaskSpecificSeedance25Rules(){var profile=new ProviderCapabilityRegistry().profile("doubao-seedance-2-5-260628");var pack=new ProviderRulePackResolver(new RuntimeRulePackLoader()).resolve(profile,VideoTaskType.FIRST_LAST_FRAME_GENERATE);assertThat(pack.namespace()).isEqualTo(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_25);assertThat(pack.ruleIds()).contains("material-authority","parameter-separation","spatial-keyframes","locked-routing");assertThat(pack.content()).doesNotContain("Seedance 2.0");}
}
