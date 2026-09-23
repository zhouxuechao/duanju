package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface NovelIntelligenceProvider {
    Result execute(NovelPromptCompiler.Compiled prompt);
    String cacheIdentity(String modelRole);
    record Result(ObjectNode value,String provider,String model,String requestId,String finishReason,long inputTokens,long outputTokens,boolean simulated){}
}
