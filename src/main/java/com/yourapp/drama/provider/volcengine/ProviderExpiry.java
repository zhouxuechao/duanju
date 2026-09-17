package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

final class ProviderExpiry {
    private ProviderExpiry() {}
    static Instant expiry(JsonNode response, String url, Instant created, Duration fallbackTtl) {
        // Parse expiry for validation only. The returned URL remains the original opaque string.
        for (String field : new String[]{"expires_at", "url_expires_at"}) {
            JsonNode value = response.get(field);
            if (value != null && value.isNumber()) return Instant.ofEpochSecond(value.asLong());
            if (value != null && value.isTextual()) try { return Instant.parse(value.asText()); } catch (Exception ignored) {}
        }
        try {
            String query = URI.create(url).getRawQuery();
            if (query != null) for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && (pair[0].equalsIgnoreCase("Expires") || pair[0].equalsIgnoreCase("x-expires")))
                    return Instant.ofEpochSecond(Long.parseLong(URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {}
        return created != null && !fallbackTtl.isZero() ? created.plus(fallbackTtl) : null;
    }
    static Instant timestamp(JsonNode json, String field) {
        JsonNode value = json.path(field);
        return value.isIntegralNumber() && value.asLong() > 0 ? Instant.ofEpochSecond(value.asLong()) : null;
    }
}
