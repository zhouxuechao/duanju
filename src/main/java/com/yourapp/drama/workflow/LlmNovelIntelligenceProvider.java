package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="llm")
public class LlmNovelIntelligenceProvider implements NovelIntelligenceProvider {
    private final LlmGateway gateway;private final VolcengineProperties properties;
    public LlmNovelIntelligenceProvider(LlmGateway gateway,VolcengineProperties properties){this.gateway=gateway;this.properties=properties;}
    @Override public Result execute(NovelPromptCompiler.Compiled prompt){
        var response=gateway.generate(new LlmGateway.StructuredRequest(prompt.systemPrompt(),prompt.userPrompt(),prompt.schema(),Map.of("modelRole",prompt.modelRole())),JsonNode.class);
        var usage=response.usage();
        return new Result((ObjectNode)response.value(),"VOLCENGINE",response.model(),response.requestId(),usage.finishReason(),usage.promptTokens(),usage.completionTokens(),response.simulated());
    }
    @Override public String cacheIdentity(String role){String selected=switch(role){case "novel_analysis"->properties.getNovelAnalysisModel();case "novel_adaptation"->properties.getNovelAdaptationModel();case "novel_screenwriter"->properties.getNovelScreenwriterModel();default->properties.getTextModel();};return selected==null||selected.isBlank()?properties.getTextModel():selected;}
}
