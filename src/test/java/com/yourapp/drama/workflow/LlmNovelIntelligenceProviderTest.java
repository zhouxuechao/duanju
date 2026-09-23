package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmNovelIntelligenceProviderTest {
    @Test void boundsAnalysisOutputForResponsesApi(){
        VolcengineProperties properties=properties();properties.setNovelAnalysisApiStyle("responses");LlmGateway gateway=gateway();LlmNovelIntelligenceProvider provider=new LlmNovelIntelligenceProvider(gateway,properties,mock(NovelIntelligenceProviderGate.class));
        provider.execute(prompt("CHUNK_ANALYSIS","novel_analysis"));
        ArgumentCaptor<LlmGateway.StructuredRequest> request=ArgumentCaptor.forClass(LlmGateway.StructuredRequest.class);verify(gateway).generate(request.capture(),eq(JsonNode.class));assertThat(request.getValue().options()).containsEntry("modelRole","novel_analysis").containsEntry("max_output_tokens",2048).doesNotContainKey("max_tokens");
    }
    @Test void boundsScreenplayOutputForChatApi(){
        VolcengineProperties properties=properties();properties.setNovelScreenwriterApiStyle("chat");LlmGateway gateway=gateway();LlmNovelIntelligenceProvider provider=new LlmNovelIntelligenceProvider(gateway,properties,mock(NovelIntelligenceProviderGate.class));
        provider.execute(prompt("EPISODE_SCREENPLAY","novel_screenwriter"));
        ArgumentCaptor<LlmGateway.StructuredRequest> request=ArgumentCaptor.forClass(LlmGateway.StructuredRequest.class);verify(gateway).generate(request.capture(),eq(JsonNode.class));assertThat(request.getValue().options()).containsEntry("modelRole","novel_screenwriter").containsEntry("max_tokens",4096).doesNotContainKey("max_output_tokens");
    }
    private static VolcengineProperties properties(){VolcengineProperties value=new VolcengineProperties();value.setTextModel("fallback");value.setTextApiStyle("responses");value.setNovelAnalysisModel("analysis");value.setNovelScreenwriterModel("screenwriter");return value;}
    @SuppressWarnings("unchecked") private static LlmGateway gateway(){LlmGateway gateway=mock(LlmGateway.class);when(gateway.generate(any(LlmGateway.StructuredRequest.class),eq(JsonNode.class))).thenReturn(new LlmGateway.StructuredResult<JsonNode>(obj(),"model","req","{}",true,new LlmGateway.ProviderUsage("stop",1,1,2)));return gateway;}
    private static NovelPromptCompiler.Compiled prompt(String task,String role){return new NovelPromptCompiler.Compiled(task,role,"v1","system","user",Map.of("type","object"));}
}
