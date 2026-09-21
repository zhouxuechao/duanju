package com.yourapp.drama.production;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class DialogueTimingEstimatorTest {
 @Test void includesPunctuationAndEmotionPauses(){var e=new DialogueTimingEstimator();assertThat(e.estimateSeconds("等等，别开门！",4.5,0.4)).isGreaterThan(1.5);assertThat(e.reconcile(4.0,4.15).action()).isEqualTo(DialogueTimingEstimator.ReconciliationAction.TIMELINE_NUDGE);assertThat(e.reconcile(4.0,7.0).action()).isEqualTo(DialogueTimingEstimator.ReconciliationAction.MANUAL_REVIEW);}
}
