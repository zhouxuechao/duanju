package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class AssetDependencyTest {
 @Test void assetRequirementsScaleWithContinuityNeeds(){var a=new AssetDependencyAnalyzer();assertThat(a.classify(0,1,1,false,false)).isEqualTo(AssetDependencyAnalyzer.Level.A0);assertThat(a.classify(1,1,1,false,false)).isEqualTo(AssetDependencyAnalyzer.Level.A1);assertThat(a.classify(2,2,2,true,false)).isEqualTo(AssetDependencyAnalyzer.Level.A2);assertThat(a.classify(8,8,30,true,true)).isEqualTo(AssetDependencyAnalyzer.Level.A3);}
}
