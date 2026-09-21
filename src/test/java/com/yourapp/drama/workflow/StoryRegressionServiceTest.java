package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class StoryRegressionServiceTest {
    @Test void fiveDistinctStoryTypesKeepTheirOwnStructuralContracts(){
        var service=new StoryRegressionService(new ObjectMapper(),new StoryProfilePolicy(),new EpisodeFormatResolver(),new ScreenwritingRuleResolver());
        var report=service.run(Path.of("evaluation"));
        assertThat(report.path("status").asText()).isEqualTo("PASS");
        assertThat(report.path("fixtureCount").asInt()).isEqualTo(5);
        assertThat(report.path("results")).extracting(v->v.path("storyType").asText()).containsExactlyInAnyOrder("IDENTITY_REVERSAL","REVENGE","SWEET_ROMANCE","SUSPENSE_MYSTERY","COMEDY");
        assertThat(report.path("results")).extracting(v->v.path("current").path("episodeFormatFamily").asText()).containsExactlyInAnyOrder("MICRO","MANJU","STANDARD","LONG","CUSTOM");
        assertThat(report.path("results")).allSatisfy(v->{assertThat(v.path("passed").asBoolean()).isTrue();assertThat(v.path("current").path("coreLoop").asText()).isNotBlank();});
    }

    @Test void blockingCountsFixturesSeparatelyFromIndividualIssues(@TempDir Path root)throws Exception{
        Path fixture=root.resolve("fixtures/broken"),baseline=root.resolve("baselines");Files.createDirectories(fixture);Files.createDirectories(baseline);
        Files.writeString(fixture.resolve("fixture.json"),"""
            {"episodeCount":1,"targetDuration":24,"distributionProfile":"GENERAL","storyProfile":{"storyType":"COMEDY","tropes":[]},"expectedInvariants":{"requiredRuleId":"MISSING_RULE","coreLoopMustContain":"不存在的循环"}}
            """);
        Files.writeString(baseline.resolve("broken.json"),"""
            {"storyType":"REVENGE","episodeFormatFamily":"LONG"}
            """);
        var report=new StoryRegressionService(new ObjectMapper(),new StoryProfilePolicy(),new EpisodeFormatResolver(),new ScreenwritingRuleResolver()).run(root);
        assertThat(report.path("fixtureCount").asInt()).isEqualTo(1);assertThat(report.path("passedCount").asInt()).isZero();assertThat(report.path("blockingRegressionCount").asInt()).isEqualTo(1);assertThat(report.path("blockingIssueCount").asInt()).isGreaterThan(1);
    }
}
