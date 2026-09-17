package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VideoFrameExtractorTest {
    @Test void samplesStartQuarterMiddleThreeQuarterAndActualEnd(){
        assertThat(VideoFrameExtractor.sampleTimes(8)).containsExactly(0d,2d,4d,6d,7.96d);
    }
}
