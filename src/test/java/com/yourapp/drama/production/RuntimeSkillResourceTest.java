package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RuntimeSkillResourceTest {
    private static final List<String> REQUIRED = List.of(
            "00-premise-analysis", "01-story-planning", "03-episode-planning",
            "04-script-writing", "05-story-quality", "06-shot-planning",
            "09-character-design", "10-location-design", "11-prop-design");

    @Test void everyRuntimeSkillIsPackagedOnTheApplicationClasspath() throws Exception {
        for (String skill : REQUIRED) {
            ClassPathResource resource = new ClassPathResource("development-skills/" + skill + "/prompt.md");
            assertThat(resource.exists()).as(skill + " exists").isTrue();
            assertThat(resource.isReadable()).as(skill + " is readable").isTrue();
            try (var input = resource.getInputStream()) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).as(skill + " is not empty").isNotBlank();
            }
        }
        ClassPathResource upstream = new ClassPathResource("development-skills/vendor/short-drama-factory/UPSTREAM.md");
        assertThat(upstream.exists()).as("vendor upstream trace exists").isTrue();
        assertThat(upstream.isReadable()).as("vendor upstream trace is readable").isTrue();
    }
    @Test void directorPromptDoesNotTurnProviderClipLimitsIntoCreativeRules() throws Exception {
        ClassPathResource resource=new ClassPathResource("development-skills/06-shot-planning/prompt.md");
        String prompt;
        try(var input=resource.getInputStream()){prompt=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        assertThat(prompt).doesNotContain("时长 2～5 秒");
        assertThat(prompt).contains("sceneTargetDurationSeconds","节拍");
    }
    @Test void runtimeCatalogDoesNotAdvertiseTheRetiredTwoToFiveSecondRule() throws Exception {
        ClassPathResource resource=new ClassPathResource("production-skills/catalog.json");String catalog;
        try(var input=resource.getInputStream()){catalog=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        assertThat(catalog).doesNotContain("镜头 2 至 5 秒").contains("REACTION_SHOT","reactionPriority");
    }
    @Test void editingSkillUsesTheFormalTimelineOperations() throws Exception {
        ClassPathResource resource=new ClassPathResource("development-skills/26-timeline-planning/prompt.md");String prompt;
        try(var input=resource.getInputStream()){prompt=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        assertThat(prompt).contains("J_CUT","L_CUT","AUDIO_BRIDGE","DIALOGUE_GAP","CLIP_REPLACE","Timeline");
    }
}
