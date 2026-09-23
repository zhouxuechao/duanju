package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class NovelIntelligenceRoutingIntegrationTest {
    @Autowired NovelIntelligenceProvider provider;
    @Autowired NovelIntelligenceExecutor executor;
    @Autowired NovelPromptCompiler compiler;
    @Autowired DocumentStore store;

    @Test void testProfileIsDeterministicAndPersistsCompleteRequestProvenance(){
        assertThat(provider).isInstanceOf(DeterministicNovelIntelligenceProvider.class);
        ObjectNode project=store.create(PROJECT,obj().put("name","N7 routing"));
        NovelPromptIR ir=new NovelPromptIR("11111111-1111-1111-1111-111111111111",List.of("chapter-1"),List.of("chunk-1"),List.of("character-1"),List.of("fact-1"),"STANDARD","SHORT_DRAMA",90,"ctx","UNTRUSTED_NOVEL_TEXT",List.of("bounded"),"自然语言原文");
        var result=executor.execute(id(project),ir,compiler.compileChunkAnalysis(ir));
        assertThat(result.value().path("summary").asText()).isNotBlank();
        ObjectNode audit=store.list(NOVEL_AI_JOB,id(project),null).getFirst();
        assertThat(audit.path("taskType").asText()).isEqualTo("CHUNK_ANALYSIS");
        assertThat(audit.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(audit.path("model").asText()).isEqualTo("deterministic-novel");
        assertThat(audit.path("providerRequestId").asText()).isNotBlank();
        assertThat(audit.path("compilerVersion").asText()).isEqualTo(NovelPromptCompiler.CHUNK_ANALYSIS_VERSION);
        assertThat(audit.path("contextHash").asText()).isEqualTo("ctx");
        assertThat(audit.path("sourceIds").path("chunkIds")).containsExactly(textNode("chunk-1"));
    }
    private static com.fasterxml.jackson.databind.node.TextNode textNode(String value){return com.fasterxml.jackson.databind.node.TextNode.valueOf(value);}
}
