package com.yourapp.drama.provider.volcengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ArkHttpClient {
    private final ObjectMapper mapper;
    private final VolcengineProperties properties;
    private final HttpClient client;

    public ArkHttpClient(ObjectMapper mapper, VolcengineProperties properties) {
        properties.validateTransport();
        this.mapper = mapper;
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Response exchange(String method, String path, Map<String, Object> body, boolean billableSubmission) {
        String encoded;
        try { encoded = body == null ? "" : mapper.writeValueAsString(body); }
        catch (Exception e) { throw ProviderException.invalid("INVALID_REQUEST", "请求无法编码为 JSON"); }
        String base = properties.getBaseUrl().toString().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.getRequestTimeout()).header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try { response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("INTERRUPTED", "模型请求被中断；提交结果需要核对", null, 0, false, billableSubmission);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ProviderException("REQUEST_TIMEOUT", "模型请求超过 " + properties.getRequestTimeout().toSeconds() + " 秒未完成；请核对服务商记录后再重试", null, 0, !billableSubmission, billableSubmission);
        } catch (IOException e) {
            throw new ProviderException("NETWORK_ERROR", "模型网络请求未完成（" + e.getClass().getSimpleName() + "）；提交结果需要核对", null, 0, !billableSubmission, billableSubmission);
        }
        int status = response.statusCode();
        String requestId = response.headers().firstValue("x-request-id")
                .or(() -> response.headers().firstValue("x-tt-logid")).orElse(null);
        JsonNode json;
        try { json = response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body()); }
        catch (Exception e) {
            boolean uncertain = billableSubmission && (status < 300 || status >= 500 || status == 408);
            throw new ProviderException("INVALID_RESPONSE", "模型服务返回了无法解析的响应", requestId, status,
                    !uncertain && (status == 429 || status >= 500), uncertain);
        }
        if (requestId == null) requestId = text(json.path("request_id"));
        if (requestId == null) requestId = text(json.path("error").path("request_id"));
        // Failed task objects legitimately contain error alongside id/status.
        if (status < 200 || status >= 300 || (json.hasNonNull("error") && !json.hasNonNull("status"))) {
            boolean uncertain = billableSubmission && (status >= 500 || status == 408);
            String code = text(json.path("error").path("code"));
            String message = text(json.path("error").path("message"));
            if (message != null && properties.getApiKey() != null) message = message.replace(properties.getApiKey(), "[redacted]");
            throw new ProviderException(code == null ? "HTTP_" + status : code,
                    message == null ? "模型服务请求失败（HTTP " + status + "）" : message,
                    requestId, status, !uncertain && (status == 429 || status == 408 || status >= 500), uncertain);
        }
        return new Response(json, requestId);
    }
    public static String text(JsonNode value) { return value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
    public record Response(JsonNode json, String requestId) {}
}
