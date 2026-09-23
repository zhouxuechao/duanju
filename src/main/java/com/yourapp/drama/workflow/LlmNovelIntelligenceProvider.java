package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.LlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="llm")
public class LlmNovelIntelligenceProvider implements NovelIntelligenceProvider {
    private final LlmGateway gateway;
    public LlmNovelIntelligenceProvider(LlmGateway gateway){this.gateway=gateway;}
    @Override public Result execute(NovelPromptCompiler.Compiled prompt){
        var response=gateway.generate(new LlmGateway.StructuredRequest(prompt.systemPrompt(),prompt.userPrompt(),prompt.schema(),Map.of("modelRole",prompt.modelRole())),JsonNode.class);
        var usage=response.usage();
        return new Result((ObjectNode)response.value(),"VOLCENGINE",response.model(),response.requestId(),usage.finishReason(),usage.promptTokens(),usage.completionTokens(),response.simulated());
    }
}
