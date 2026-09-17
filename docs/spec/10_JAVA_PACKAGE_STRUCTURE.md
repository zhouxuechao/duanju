# 10 Java 包结构与接口

```text
com.yourapp.drama

├── project
├── story
├── episode
├── scene
├── shot

├── character
├── location
├── prop
├── asset
├── dialogue
├── voice

├── agent
├── skill
├── workflow

├── continuity
├── prompt
├── qc

├── model
│   ├── llm
│   ├── image
│   ├── video
│   └── voice

├── provider
│   └── volcengine

├── job
├── storage
├── timeline
├── render
├── cost
└── api
```

---

## Adapter 接口

### LLM

```java
public interface LlmGateway {
    <T> T generateStructured(
        String systemPrompt,
        String userPrompt,
        Class<T> outputType
    );
}
```

### Image

```java
public interface ImageGenerator {
    ImageGenerationResult generate(ImageGenerationRequest request);
}
```

### Video

```java
public interface VideoGenerator {
    VideoGenerationResult generate(VideoGenerationRequest request);
}
```

### Voice

```java
public interface VoiceGenerator {
    VoiceGenerationResult generate(VoiceGenerationRequest request);
}
```

---

## 火山图片结果

```java
public record ImageGenerationResult(
    String provider,
    String model,
    String providerRequestId,
    String providerUrl,
    Instant providerUrlExpiresAt,
    Map<String, Object> rawMetadata
) {}
```

关键：
`providerUrl` 必须保留原样交给 Seedance。

---

## 视频请求

```java
public record VideoGenerationRequest(
    String shotId,
    String prompt,
    String firstFrameProviderUrl,
    List<String> referenceAssets,
    int durationSeconds,
    Map<String, Object> providerOptions
) {}
```

不要在 request builder 中自动做：
- download
- image compression
- OSS upload
- URL rewrite

---

## Keyframe Handoff Service

```java
public interface KeyframeHandoffService {

    VideoJob createVideoJobFromProviderKeyframe(
        String keyframeId
    );
}
```

该 Service 必须校验：
- keyframe provider == VOLCENGINE
- providerUrl 非空
- providerUrl 未过期
- keyframe QC 已通过
- keyframe 已锁定
