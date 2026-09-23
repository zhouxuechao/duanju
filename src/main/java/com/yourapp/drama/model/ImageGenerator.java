package com.yourapp.drama.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ImageGenerator {
    ImageResult generate(ImageRequest request);

    record ImageRequest(String modelId,String prompt, List<String> referenceImageUrls, Map<String, Object> options) {
        public ImageRequest(String prompt,List<String> referenceImageUrls,Map<String,Object> options){this(null,prompt,referenceImageUrls,options);}
        public ImageRequest {
            referenceImageUrls = referenceImageUrls == null ? List.of() : List.copyOf(referenceImageUrls);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    /** providerUrl is opaque: never normalize, decode, proxy, download or re-upload it in handoff. */
    record ImageResult(String providerUrl, Instant expiresAt, String requestId, String model, boolean simulated) {}
}
