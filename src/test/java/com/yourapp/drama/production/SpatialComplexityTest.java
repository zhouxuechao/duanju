package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class SpatialComplexityTest {
 @Test void topViewIsOnlyRequiredForS4(){var a=new SpatialComplexityAnalyzer();assertThat(a.classify(1,0,false,false)).isEqualTo(SpatialComplexityAnalyzer.Level.S0);assertThat(a.classify(2,0,false,false)).isEqualTo(SpatialComplexityAnalyzer.Level.S1);assertThat(a.classify(5,4,true,true)).isEqualTo(SpatialComplexityAnalyzer.Level.S4);assertThat(a.requiredRuleIds(SpatialComplexityAnalyzer.Level.S1)).doesNotContain("director-topview");assertThat(a.requiredRuleIds(SpatialComplexityAnalyzer.Level.S4)).contains("director-topview");}
}
