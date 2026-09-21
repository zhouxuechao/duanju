package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ShotComplexityTest {
 @Test void classifiesSimpleComplexAndImpossibleShots(){var a=new ShotComplexityAnalyzer();assertThat(a.analyze(new ShotComplexityAnalyzer.Input(1,1,0,0,0,0,0,0)).decision()).isEqualTo(ShotComplexityAnalyzer.Decision.KEEP);assertThat(a.analyze(new ShotComplexityAnalyzer.Input(3,3,2,1,1,2,2,2)).decision()).isEqualTo(ShotComplexityAnalyzer.Decision.SPLIT_RECOMMENDED);assertThat(a.analyze(new ShotComplexityAnalyzer.Input(6,6,4,3,3,4,4,4)).decision()).isEqualTo(ShotComplexityAnalyzer.Decision.BLOCK_TOO_COMPLEX);}
}
