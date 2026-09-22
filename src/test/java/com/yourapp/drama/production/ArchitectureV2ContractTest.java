package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureV2ContractTest {
    @Test void architectureDocumentNamesEveryTruthSourceAndProductionFlow() throws Exception{
        String text=Files.readString(Path.of("docs/architecture-v2.md"));
        assertThat(text).contains("Story SSOT","Continuity SSOT","Production SSOT","Timeline SSOT")
            .contains("Prompt Flow","Provider Flow","Task Flow","QC Flow","Repair Flow","Render Flow")
            .contains("```mermaid","GenerationJob","PromptIR","RuleEngine","Timeline");
    }
}
