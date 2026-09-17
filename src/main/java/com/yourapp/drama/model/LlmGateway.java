package com.yourapp.drama.model;

import java.util.Map;

public interface LlmGateway {
    <T> StructuredResult<T> generate(StructuredRequest request, Class<T> responseType);

    record StructuredRequest(String systemPrompt, String userPrompt,
                             Map<String, Object> jsonSchema, Map<String, Object> options) {
        public StructuredRequest {
            jsonSchema = jsonSchema == null ? Map.of() : Map.copyOf(jsonSchema);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    record ProviderUsage(String finishReason,long promptTokens,long completionTokens,long totalTokens) {
        public static ProviderUsage unknown(){return new ProviderUsage("unknown",-1,-1,-1);}
    }
    record StructuredResult<T>(T value, String model, String requestId, String rawJson, boolean simulated,ProviderUsage usage) {
        public StructuredResult(T value,String model,String requestId,String rawJson,boolean simulated){this(value,model,requestId,rawJson,simulated,ProviderUsage.unknown());}
    }
}
