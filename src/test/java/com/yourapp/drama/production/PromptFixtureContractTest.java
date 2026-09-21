package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PromptFixtureContractTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void compilerAndSequenceFixturesAreParseableAndDeclareBehaviorContracts() throws Exception{
        List<Path> files;
        try(var stream=Files.walk(Path.of("test-fixtures"))){files=stream.filter(p->p.toString().endsWith(".json")&&(p.toString().contains("prompt-compiler")||p.toString().contains("sequence"))).sorted().toList();}
        assertThat(files).hasSize(17);Set<String> ids=new HashSet<>();
        for(Path file:files){JsonNode fixture=mapper.readTree(file.toFile());assertThat(ids.add(fixture.path("fixtureId").asText())).as(file.toString()).isTrue();assertThat(fixture.path("compiler").asText()).isIn("SEEDREAM","SEEDANCE","SEEDANCE_SEQUENCE");assertThat(fixture.path("expectedSections")).isNotEmpty();assertThat(fixture.path("invariant").asText()).isNotBlank();if(file.toString().contains("sequence"))assertThat(fixture.path("paidProviderAllowed").asBoolean()).isFalse();}
    }
}
