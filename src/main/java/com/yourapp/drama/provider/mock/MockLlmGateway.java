package com.yourapp.drama.provider.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import java.util.Map;
import java.util.UUID;

/** Deterministic local adapter. Every result is explicitly marked simulated. */
public final class MockLlmGateway implements LlmGateway {
    private final ObjectMapper mapper;
    public MockLlmGateway(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public <T> StructuredResult<T> generate(StructuredRequest request, Class<T> responseType) {
        if (request.userPrompt() == null || request.userPrompt().isBlank()) throw ProviderException.invalid("PROMPT_REQUIRED", "提示词不能为空");
        ObjectNode result = mapper.createObjectNode();
        result.put("simulated", true).put("notice", "本结果来自本地模拟适配器，不代表火山模型生成质量");
        try{var input=mapper.readTree(request.userPrompt());if(input.path("pipelineVersion").asInt()==2)result=StoryDevelopmentDemo.generate(input);}catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
        if (responseType == ObjectNode.class || responseType == com.fasterxml.jackson.databind.JsonNode.class) {
            @SuppressWarnings("unchecked") T value = (T) result;
            try { return new StructuredResult<>(value, "mock-local", "mock-" + UUID.nameUUIDFromBytes(request.userPrompt().getBytes()), mapper.writeValueAsString(result), true); }
            catch (Exception e) { throw new ProviderException("MOCK_ENCODING", "模拟结果编码失败", null, 0, false, false); }
        }
        try {
            T value = mapper.treeToValue(result, responseType);
            return new StructuredResult<>(value, "mock-local", "mock-" + UUID.nameUUIDFromBytes(request.userPrompt().getBytes()), mapper.writeValueAsString(result), true);
        } catch (Exception e) { throw new ProviderException("MOCK_SCHEMA", "模拟适配器无法构造请求的输出类型；请使用真实模式或提供演示模型", null, 0, false, false); }
    }
}
