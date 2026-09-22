package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBoundaryContractTest {
    @Test void workflowAndProviderAdaptersCannotRecreateTheRetiredBusinessPromptBuilders() throws Exception{
        String asset=Files.readString(Path.of("src/main/java/com/yourapp/drama/workflow/AssetViewService.java"));
        String post=Files.readString(Path.of("src/main/java/com/yourapp/drama/workflow/PostProductionService.java"));
        String voiceProvider=Files.readString(Path.of("src/main/java/com/yourapp/drama/provider/audio/SeedAudioVoiceGenerator.java"));
        String director=Files.readString(Path.of("src/main/java/com/yourapp/drama/job/DirectorGenerationService.java"));
        String story=Files.readString(Path.of("src/main/java/com/yourapp/drama/workflow/StoryDevelopmentService.java"));
        String storyQa=Files.readString(Path.of("src/main/java/com/yourapp/drama/workflow/StoryQualityService.java"));

        assertThat(asset).contains("prompts.compileAssetReference(input)","compiled.promptIRJson()")
                .doesNotContain("private String prompt(","loadPrompt(String kind)","locationCameraInstruction(String view)");
        assertThat(post).contains("prompts.compileVoice(compileRequest)","prompts.compileLipSync(compileRequest)","compiled.promptIRJson()");
        assertThat(voiceProvider).contains("request.prompt()")
                .doesNotContain("真人短剧的一条同期对白录音任务","唯一允许说出的对白：","发音要求：");
        assertThat(director).contains("prompts.compileDirector(").doesNotContain("private String prompt()");
        assertThat(story).contains("prompts.compileStory(","prompts.storySkillText(").doesNotContain("private String prompt(String type)");
        assertThat(storyQa).contains("prompts.compileStory(\"STORY_QA\"","prompts.storySkillText(\"STORY_QA\")").doesNotContain("private String prompt()");
    }
}
