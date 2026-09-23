package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name="drama.novel.intelligence.mode",havingValue="llm")
public class LlmNovelIntelligenceProvider implements NovelIntelligenceProvider {
    private final LlmGateway gateway;private final VolcengineProperties properties;private final NovelIntelligenceProviderGate gate;
    public LlmNovelIntelligenceProvider(LlmGateway gateway,VolcengineProperties properties,NovelIntelligenceProviderGate gate){this.gateway=gateway;this.properties=properties;this.gate=gate;}
    @Override public Result execute(NovelPromptCompiler.Compiled prompt){
        gate.validate(prompt.modelRole());
        int maxOutput=switch(prompt.taskType()){case "CHUNK_ANALYSIS","CHAPTER_SYNTHESIS"->2048;default->4096;};
        Map<String,Object> options=new LinkedHashMap<>();options.put("modelRole",prompt.modelRole());options.put("responses".equals(apiStyle(prompt.modelRole()))?"max_output_tokens":"max_tokens",maxOutput);
        var response=gateway.generate(new LlmGateway.StructuredRequest(prompt.systemPrompt(),prompt.userPrompt(),prompt.schema(),options),JsonNode.class);
        var usage=response.usage();
        return new Result((ObjectNode)response.value(),"VOLCENGINE",response.model(),response.requestId(),usage.finishReason(),usage.promptTokens(),usage.completionTokens(),response.simulated());
    }
    @Override public String cacheIdentity(String role){String selected=switch(role){case "novel_analysis"->properties.getNovelAnalysisModel();case "novel_adaptation"->properties.getNovelAdaptationModel();case "novel_screenwriter"->properties.getNovelScreenwriterModel();default->properties.getTextModel();};return selected==null||selected.isBlank()?properties.getTextModel():selected;}
    private String apiStyle(String role){String selected=switch(role){case "novel_analysis"->properties.getNovelAnalysisApiStyle();case "novel_adaptation"->properties.getNovelAdaptationApiStyle();case "novel_screenwriter"->properties.getNovelScreenwriterApiStyle();default->properties.getTextApiStyle();};return selected==null||selected.isBlank()?properties.getTextApiStyle():selected;}
}
