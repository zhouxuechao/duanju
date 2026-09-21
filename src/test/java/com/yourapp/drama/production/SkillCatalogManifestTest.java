package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogManifestTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void manifestOwnsCatalogAndGeneratorNeverOverwritesProductionPrompts() throws Exception {
        Path root=Path.of("skills"),manifestPath=root.resolve("manifest.json");
        assertThat(manifestPath).isRegularFile();
        JsonNode manifest=mapper.readTree(manifestPath.toFile());
        Set<String> declared=new HashSet<>();
        for(JsonNode skill:manifest.path("skills")){
            for(String field:java.util.List.of("skillId","version","phase","runtime","promptPath","schemaPath","rulePackDependencies","source","fingerprint"))assertThat(skill.has(field)).as("%s.%s",skill.path("skillId").asText(),field).isTrue();
            declared.add(skill.path("skillId").asText());
        }
        try(var paths=Files.walk(root)){
            Set<String> prompts=paths.filter(path->path.getFileName().toString().equals("prompt.md")&&!path.toString().contains("vendor"))
                .map(path->root.relativize(path).getName(0).toString()).collect(java.util.stream.Collectors.toSet());
            assertThat(declared).containsAll(prompts);
        }
        String generator=Files.readString(Path.of("scripts/generate_skill_pack.py"));
        assertThat(generator).contains("manifest.json").doesNotContain("write_text(prompt").doesNotContain("SKILLS = [");
    }

    @Test void validatorUsesManifestCardinalityInsteadOfTheLegacyCatalogConstant() throws Exception {
        JsonNode manifest=mapper.readTree(Path.of("skills/manifest.json").toFile());
        String validator=Files.readString(Path.of("scripts/validate_skill_pack.py"));

        assertThat(validator).contains("manifest.json").contains("duplicate fingerprint")
            .doesNotContain("CATALOG =").doesNotContain("!= 27");
        Process process=new ProcessBuilder("python","scripts/validate_skill_pack.py").redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("validated "+manifest.path("skills").size()+" skills");
    }
}
