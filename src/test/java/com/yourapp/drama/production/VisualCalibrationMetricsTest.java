package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class VisualCalibrationMetricsTest {
    @Test void reportsConfusionRoutingAndCategoryAccuracyFromHumanGroundTruth(){
        var samples=List.of(
            new VisualCalibrationMetrics.Sample(true,"AUTO_PASS",Set.of(),Set.of()),
            new VisualCalibrationMetrics.Sample(false,"AUTO_PASS",Set.of("IDENTITY_MISMATCH"),Set.of()),
            new VisualCalibrationMetrics.Sample(true,"AUTO_REGENERATE",Set.of(),Set.of()),
            new VisualCalibrationMetrics.Sample(false,"MANUAL_REVIEW",Set.of("LOCATION_MISMATCH"),Set.of("LOCATION_MISMATCH")));
        var metrics=new VisualCalibrationMetrics().calculate(samples);
        assertThat(metrics.path("groundTruthSamples").asInt()).isEqualTo(4);
        assertThat(metrics.path("passPrecision").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("passRecall").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("falsePassRate").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("falseRejectRate").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("autoPassRate").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("autoRegenerateRate").asDouble()).isEqualTo(.25);
        assertThat(metrics.path("manualReviewRate").asDouble()).isEqualTo(.25);
        assertThat(metrics.path("failureCodeGroundTruthSamples").asInt()).isEqualTo(2);
        assertThat(metrics.path("categoryGroundTruthSamples").asInt()).isEqualTo(4);
        assertThat(metrics.path("failureCodeAccuracy").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("categoryAccuracy").path("identity").asDouble()).isEqualTo(.75);
        assertThat(metrics.path("categoryAccuracy").path("location").asDouble()).isEqualTo(1.0);
        assertThat(metrics.path("categoryPositiveGroundTruthSamples").path("identity").asInt()).isEqualTo(1);
        assertThat(metrics.path("categoryPositiveGroundTruthSamples").path("location").asInt()).isEqualTo(1);
        assertThat(metrics.path("categoryPositiveGroundTruthSamples").path("prop").asInt()).isZero();
    }

    @Test void doesNotTreatAnUnlabeledHumanFailureAsAConfirmedEmptyFailureCodeSet(){
        var metrics=new VisualCalibrationMetrics().calculate(List.of(
                new VisualCalibrationMetrics.Sample(false,"MANUAL_REVIEW",Set.of(),Set.of("TEXT_OR_WATERMARK"))));
        assertThat(metrics.path("failureCodeGroundTruthSamples").asInt()).isZero();
        assertThat(metrics.path("categoryGroundTruthSamples").asInt()).isZero();
        assertThat(metrics.path("failureCodeAccuracy").asDouble()).isZero();
        assertThat(metrics.path("categoryAccuracy").path("textOrWatermark").asDouble()).isZero();
        assertThat(metrics.path("categoryPositiveGroundTruthSamples").path("textOrWatermark").asInt()).isZero();
    }
}
