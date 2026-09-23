package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.ProviderException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class VolcengineImageGenerator implements ImageGenerator {
    private final ArkHttpClient http;
    private final VolcengineProperties properties;
    public VolcengineImageGenerator(ArkHttpClient http, VolcengineProperties properties) {
        this.http = http; this.properties = properties;
    }
    @Override public ImageResult generate(ImageRequest request) {
        Map<String,Object> body=requestBody(request);
        Instant submittedAt = Instant.now();
        ArkHttpClient.Response response = http.exchange("POST", "/images/generations", body, true);
        JsonNode item = response.json().path("data").path(0);
        String url = ArkHttpClient.text(item.path("url"));
        if (url == null) throw new ProviderException("MISSING_IMAGE_URL", "图片生成没有返回原始 URL，请核对模型请求结果后再决定重试", response.requestId(), 200, false, true);
        try { ProviderInputs.imageUrl(url, false); }
        catch (ProviderException e) { throw new ProviderException("INVALID_IMAGE_URL", "图片生成返回的 URL 无效", response.requestId(), 200, false, true); }
        Instant created = ProviderExpiry.timestamp(response.json(), "created");
        Instant expires = ProviderExpiry.expiry(item, url, created == null ? submittedAt : created, properties.getImageUrlTtl());
        return new ImageResult(url, expires, response.requestId(), model(request), false);
    }
    public Map<String,Object> requestBodySnapshot(ImageRequest request){return Map.copyOf(requestBody(request));}
    private Map<String,Object> requestBody(ImageRequest request){
        ProviderInputs.prompt(request.prompt());
        request.referenceImageUrls().forEach(ProviderInputs::imageReference);
        Map<String, Object> body = ProviderInputs.options(request.options(), Set.of("size", "seed", "watermark", "output_format", "guidance_scale", "optimize_prompt_options"));
        body.put("model",model(request));body.put("prompt", request.prompt());body.put("response_format", "url");body.put("sequential_image_generation","disabled");body.putIfAbsent("size",properties.getImageSize());body.putIfAbsent("watermark",false);body.put("stream", false);
        if (!request.referenceImageUrls().isEmpty()) body.put("image", request.referenceImageUrls().size() == 1
                ? request.referenceImageUrls().getFirst() : request.referenceImageUrls());
        return body;
    }
    private String model(ImageRequest request){return request.modelId()==null||request.modelId().isBlank()?properties.getImageModel():request.modelId();}
}
