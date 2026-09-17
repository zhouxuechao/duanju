package com.yourapp.drama.provider.volcengine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("drama.provider.volcengine")
public class VolcengineProperties {
    private URI baseUrl = URI.create("https://ark.cn-beijing.volces.com/api/v3");
    private String apiKey;
    private String textModel;
    private String directorModel;
    private String imageModel;
    private String videoModel;
    private String vlmModel;
    private String textApiStyle = "responses";
    private String directorApiStyle = "responses";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofMinutes(10);
    private int maxOutputTokens = 16384;
    private Duration imageUrlTtl = Duration.ofHours(24);
    private Duration videoUrlTtl = Duration.ofHours(24);
    /** Durations accepted by the configured video model; requests are rounded up to one of these. */
    private List<Integer> videoSupportedDurations = List.of(4, 8, 12);

    public void validate() {
        if (apiKey == null || apiKey.isBlank() || textModel == null || textModel.isBlank()
                || imageModel == null || imageModel.isBlank() || videoModel == null || videoModel.isBlank())
            throw new IllegalStateException("真实火山模式需要 API Key、text-model、image-model、video-model；请配置可用模型 ID，不能使用演示默认值。");
        if (!"responses".equals(textApiStyle) && !"chat".equals(textApiStyle))
            throw new IllegalStateException("text-api-style 必须为 responses 或 chat");
        if (!"responses".equals(directorApiStyle) && !"chat".equals(directorApiStyle))
            throw new IllegalStateException("director-api-style 必须为 responses 或 chat");
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || baseUrl.getUserInfo() != null || !("https".equals(baseUrl.getScheme())
                || ("http".equals(baseUrl.getScheme()) && ("localhost".equals(baseUrl.getHost()) || "127.0.0.1".equals(baseUrl.getHost())))))
            throw new IllegalStateException("base-url 必须使用 HTTPS（本地测试允许 HTTP）且不得含凭据、查询参数或片段");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalStateException("模型连接和请求超时必须大于零");
        if (maxOutputTokens < 1024 || maxOutputTokens > 65536)
            throw new IllegalStateException("模型最大输出 token 数应为 1024 到 65536");
        if (imageUrlTtl == null || imageUrlTtl.isNegative() || videoUrlTtl == null || videoUrlTtl.isNegative())
            throw new IllegalStateException("URL 有效期配置不得为负数");
        if (videoSupportedDurations == null || videoSupportedDurations.isEmpty() || videoSupportedDurations.stream().anyMatch(v -> v == null || v < 1 || v > 60)
                || videoSupportedDurations.stream().distinct().count() != videoSupportedDurations.size())
            throw new IllegalStateException("video-supported-durations 必须是 1 至 60 秒的非重复列表");
    }
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getTextModel() { return textModel; }
    public void setTextModel(String value) { textModel = value; }
    public String getDirectorModel() { return directorModel; }
    public void setDirectorModel(String value) { directorModel = value; }
    public String getImageModel() { return imageModel; }
    public void setImageModel(String value) { imageModel = value; }
    public String getVideoModel() { return videoModel; }
    public void setVideoModel(String value) { videoModel = value; }
    public String getVlmModel() { return vlmModel; }
    public void setVlmModel(String value) { vlmModel = value; }
    public void validateVlm(){if(vlmModel==null||vlmModel.isBlank())throw new IllegalStateException("真实视觉质检需要配置 vlm-model");}
    public String getTextApiStyle() { return textApiStyle; }
    public void setTextApiStyle(String value) { textApiStyle = value; }
    public String getDirectorApiStyle() { return directorApiStyle; }
    public void setDirectorApiStyle(String value) { directorApiStyle = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public Duration getImageUrlTtl() { return imageUrlTtl; }
    public void setImageUrlTtl(Duration value) { imageUrlTtl = value; }
    public Duration getVideoUrlTtl() { return videoUrlTtl; }
    public void setVideoUrlTtl(Duration value) { videoUrlTtl = value; }
    public List<Integer> getVideoSupportedDurations() { return videoSupportedDurations; }
    public void setVideoSupportedDurations(List<Integer> value) { videoSupportedDurations = value; }
}
