package com.yourapp.drama.provider.mock;

import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.ProviderException;
import java.time.Instant;
import java.util.UUID;

public final class MockImageGenerator implements ImageGenerator {
    @Override public ImageResult generate(ImageRequest request) {
        if (request.prompt() == null || request.prompt().isBlank()) throw ProviderException.invalid("PROMPT_REQUIRED", "提示词不能为空");
        String id = UUID.nameUUIDFromBytes(request.prompt().getBytes()).toString();
        // This URL intentionally remains opaque and carries a deterministic signed-query-shaped suffix.
        return new ImageResult("https://mock.volcengine.invalid/keyframes/" + id + ".png?signature=simulated&expires=" + Instant.now().plusSeconds(86400).getEpochSecond(),
                Instant.now().plusSeconds(86400), "mock-" + id, "mock-seedream", true);
    }
}
