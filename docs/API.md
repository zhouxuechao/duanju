# 当前工作流 API

服务地址默认为 `http://127.0.0.1:8080`。JSON 请求使用 `Content-Type: application/json`。所有响应中的关联 ID 都是服务端返回的 UUID；错误响应包含 `code` 和 `message`。

## 作品与工作区

使用资源接口创建作品：

```http
POST /api/resources/projects
Content-Type: application/json
```

```json
{
  "name": "村口的铃",
  "idea": "一群留守老人守着一座会在深夜响起的旧铃，铃声每晚都把一个死去的人带回村里。",
  "episodeCount": 10,
  "targetDuration": 60,
  "ratio": "9:16",
  "style": "农村恐怖",
  "dialect": "MANDARIN"
}
```

查看当前工作区和任务状态：

```http
GET /api/resources/projects/{projectId}/workspace
GET /api/resources/jobs?projectId={projectId}
GET /api/events?projectId={projectId}
```

工作区中的人物、定妆、场景和道具由故事核心确认后自动建立。生产页面不要求填写服务商人物 ID、图片路径或手工素材绑定。

作品的故事制式由三个独立对象决定：`storyProfile` 描述题材、故事发动机、受众与情绪；`episodeFormat` 描述每集时长、节拍模式与场景上限；`distributionProfile` 描述发行平台约束。它们会进入故事规则包指纹，不能靠 Java 题材分支临时改写。

```json
{
  "storyProfile": {"settingGenre":"RURAL","storyType":"SUSPENSE_MYSTERY","audience":"GENERAL","tones":["悬疑"],"intensity":"HIGH"},
  "episodeFormat": {"profileId":"MICRO_24S","family":"MICRO","targetDurationSec":24,"beatMode":"SINGLE_ROUND"},
  "distributionProfile":"GENERAL"
}
```

## 分阶段故事开发

所有故事生成都从新的故事核心开始：

```http
POST /api/story-development/projects/{projectId}/core
```

返回一个 `STORY_DOCUMENT` 草稿和对应任务。草稿生成成功只表示模型输出已保存，仍需人工审查。

系统先保存 Premise Review。不可行时文档停在 `PREMISE_REVIEW_REQUIRED`，不会创建 CORE 生成任务。处理入口：

```http
POST /api/story-development/documents/{documentId}/premise
```

`action` 为 `EDIT_IDEA`、`ACCEPT_RECOMMENDATIONS` 或 `FORCE_CONTINUE`。强制继续必须提供 `overrideBy` 与非空 `overrideReason`，并永久写入审计字段。

编辑草稿时必须带当前版本号，并提交完整的 `content`：

```http
PUT /api/story-development/documents/{documentId}
Content-Type: application/json
```

```json
{
  "revision": 2,
  "content": { "title": "村口的铃", "characters": [], "locations": [], "props": [] }
}
```

确认当前阶段：

```http
POST /api/story-development/documents/{documentId}/confirm
Content-Type: application/json
```

核心确认请求可以指定下一批集纲大小：

```json
{ "revision": 3, "batchSize": 5 }
```

确认核心后按顺序生成集纲批次；所有批次确认后才生成单集完整剧本。确认集纲或剧本会创建下一阶段任务。已确认内容不可覆盖编辑，修改会创建新版本并使受影响的下游内容变为 `STALE`。

失败文档只允许显式局部重试：

```http
POST /api/story-development/documents/{documentId}/retry
```

`submissionUncertain=true` 时禁止重试，必须先用服务商请求 ID 或任务 ID 核对记录。

## 人物、场景和道具多视图

确认核心后，在素材多视图页面生成当前核心版本的参考图。可传 `assetIds` 只处理选中的定妆、场景或道具；不传则处理当前核心下的全部素材。

```http
POST /api/asset-views/projects/{projectId}/generate
Content-Type: application/json
```

```json
{ "assetIds": ["character-look-uuid", "location-uuid"] }
```

每套参考图包含四个独立视图：人物为正面、左侧、右侧、背面；场景为布局、正向、反向、侧向；道具为正面、侧面、背面、尺度。系统先排主视图，主视图批准后才排其余视图。

批准、退回和单张重生都必须携带当前 `revision`：

```http
POST /api/asset-views/{viewId}/approve
POST /api/asset-views/{viewId}/reject
POST /api/asset-views/{viewId}/regenerate
```

批准示例：

```json
{ "revision": 2, "reviewNote": "已核对人物身份、定妆材质、比例和光线方向" }
```

退回示例：

```json
{ "revision": 2, "note": "背面衣领结构与主视图不一致" }
```

归档失败只重试保存，不重新调用图片模型：

```http
POST /api/asset-views/{viewId}/archive
```

提交状态不确定时不能重生。只有同一版本的四张视图全部人工批准，素材才会进入分镜和视频的参考集合。

## 分镜、关键帧和视频

集纲或剧本确认后才能拆镜：

```http
POST /api/scenes/{sceneId}/plan
POST /api/shots/{shotId}/storyboard
POST /api/shots/{shotId}/keyframe
POST /api/keyframes/{keyframeId}/video
```

每个镜头必须引用当前批准的素材视图，并提供完整机位计划、单一主要动作、镜头衔接枚举和上一镜头的连续性状态。素材视图、故事圣经或剧本版本过期时，服务端会在调用服务商前拒绝任务。

关键帧和视频生成成功后都必须由人查看画面再提交审查：

```http
POST /api/{kind}/{id}/review
Content-Type: application/json
```

关键帧审查示例：

```json
{ "passed": true, "score": 95, "notes": "人物、定妆、场景、道具和机位与镜头要求一致" }
```

视频审查还必须带人工复核的非空 `observedState`。未通过审查的素材不能锁定，也不能进入下一步。文本模型不会代替人查看图片或视频。

审查通过后锁定：

```http
POST /api/{kind}/{id}/lock
```

需要保留原提示词和参考视图时，可对关键帧发起局部重画：

```http
POST /api/keyframes/{keyframeId}/regenerate
```

## 声音、时间线和导出

```http
POST /api/dialogue-lines/{id}/dialect
POST /api/dialogue-lines/{id}/tts
POST /api/video-takes/{id}/lipsync
POST /api/episodes/{episodeId}/sound-design
POST /api/episodes/{episodeId}/timeline
POST /api/timelines/{timelineId}/render
```

口型同步只接受已审查并锁定的视频和已采用音频。时间线和导出同样会检查剧本、素材视图和连续性快照是否仍是当前版本。

## 任务记录与恢复

所有生成任务都会保存本地任务 ID、服务商请求 ID、服务商任务 ID、状态、校验结果和失败原因。只有明确失败且服务商未接单的任务才允许局部重试：

```http
POST /api/jobs/{jobId}/retry
POST /api/jobs/{jobId}/cancel
```

服务商已接单或提交状态不确定时，系统不会自动重复请求。生产接口不会接受密钥、服务商素材 ID 或任意模型覆盖参数。

提交状态不确定时使用人工对账，不能直接重试：

```http
POST /api/jobs/{jobId}/reconcile
```

`decision` 支持 `CONFIRMED_SUBMITTED`、`CONFIRMED_NOT_SUBMITTED`、`UNRESOLVED`；已提交时还需提供服务商任务 ID 和核对证据。

## 全链路运行、预览与终片

```http
GET  /api/projects/{projectId}/preflight?mode=MOCK
POST /api/projects/{projectId}/pipeline-runs
GET  /api/pipeline-runs/{runId}
POST /api/pipeline-runs/{runId}/resume
```

PipelineRun 按阶段保存 checkpoint、输入/输出指纹、成本和失败类别。`resume` 从首个未完成阶段继续，已经成功并锁定的生成物不会重新提交。

时间线必须先生成 PREVIEW、完成当前 `contentRevision` 的时间线质检并锁定，之后才能生成 FINAL。最终文件通过技术质检后，还必须人工提交：

```http
POST /api/timelines/{timelineId}/final-review
```

`decision` 支持 `PASS`、`REGENERATE`、`MANUAL_FIX`，并记录故事准确、视觉、动作、声音、音画同步、字幕与节奏评分。

## Accepted Deviation

视频审查发现实拍末态与计划末态不一致时，请在审查请求中提交真实 `observedState`。选择重生成时使用 `deviationDecision=REGENERATE` 且 `passed=false`；人工确认将偏差升级为后续连续性事实时使用 `deviationDecision=ACCEPT_CANONICAL`。自动质检无权接受偏差。未处理偏差会阻止视频锁定和时间线质检。
