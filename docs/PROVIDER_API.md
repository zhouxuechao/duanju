# Provider API contract

The model layer keeps provider output separate from archives. `ImageResult.providerUrl` and `VideoTask.providerUrl` are opaque provider URLs: the handoff must pass the exact string, including signed query parameters, without download, URL rewriting, proxying, compression, or re-upload. `expiresAt` is advisory and is populated from provider metadata or the configured fallback TTL; callers must reject an expired URL.

`LlmGateway` sends a strict JSON Schema request. The Volcengine adapter validates schemas before billing and validates the returned JSON after the call (required fields, types, arrays, bounds, enums, composition keywords, and `additionalProperties`). Model IDs are required configuration values. Reserved provider fields (`model`, `stream`, `messages`/`input`, response format, and content) cannot be overridden through `options`.

`ImageGenerator` calls `POST /images/generations` with Seedream. Reference images must remain original HTTP(S) URLs. `asset://asset-...` is not a Seedream image URL and is rejected. The returned URL is preserved exactly.

`VideoGenerator` calls the Ark v3 asynchronous API:

* `POST /contents/generations/tasks`
* `GET /contents/generations/tasks/{id}`
* `DELETE /contents/generations/tasks/{id}`

The request uses official `content` entries (`text`, `image_url`, `video_url`, `audio_url`) and `{url: ...}` payloads. An `asset://asset-...` URI is accepted only as a trusted reference with a `reference_*` role; it is never converted into an HTTP URL. A direct `firstFrameUrl` route cannot be mixed with the full multimodal `reference_*` route. DELETE is sent only after a poll confirms `queued`; a running task is reported as `CANCEL_NOT_ALLOWED` because the official API does not cancel running tasks.

All provider exceptions are typed. `retryable()` is true only for safe transient failures. `uncertain()` means a billable submission may have reached the provider and must be reconciled by request/task ID before retrying.

## Spring configuration

Set `drama.provider.mode=mock` (the default) for deterministic, local-only adapters. Their results carry `simulated=true`; image output uses an intentionally non-routable signed-query-shaped URL and video output uses `/demo/take.mp4`.

Set `drama.provider.mode=volcengine` to require all of:

```yaml
drama:
  provider:
    volcengine:
      base-url: https://ark.cn-beijing.volces.com/api/v3
      api-key: ${ARK_API_KEY}
      text-model: ${ARK_TEXT_MODEL}
      image-model: ${ARK_IMAGE_MODEL}
      video-model: ${ARK_VIDEO_MODEL}
      text-api-style: responses # or chat
```

The production adapter fails fast at startup when key/model IDs are missing. Model IDs intentionally have no invented defaults; model availability and endpoint IDs must be selected in the Volcengine console.

Official references (checked against the v3 SDK and documentation): [Seedream image generation](https://www.volcengine.com/docs/82379/1541523), [Seedance create/query](https://www.volcengine.com/docs/82379/1520757), [query task](https://www.volcengine.com/docs/82379/1521309), and [cancel/delete](https://www.volcengine.com/docs/82379/1521720). The SDK's typed request definitions are also available in the [official Ark runtime repository](https://github.com/volcengine/volcengine-python-sdk).
