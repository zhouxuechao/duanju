package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeVendorRulePackResourceTest {
    @Test
    void allThreeVendorRulePacksExposeRealMarkdownBodiesOnTheRuntimeClasspath() throws Exception {
        assertMarkdown("development-skills/vendor/short-drama-factory/references/character-bible.md", "角色");
        assertMarkdown("development-skills/vendor/manju-laoli-skill/short-drama-director/references/spatial-reference-system-V3.md", "空间");
        assertMarkdown("development-skills/vendor/oiuv-ai-short-drama/drama-shot-prompt/SKILL.md", "镜头");
    }

    private void assertMarkdown(String path, String expectedBodyText) throws Exception {
        var resource = new ClassPathResource(path);
        assertThat(resource.exists()).as(path).isTrue();
        String body = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).as(path + " must contain the real rule body")
            .hasSizeGreaterThan(100)
            .contains(expectedBodyText);
    }
}
