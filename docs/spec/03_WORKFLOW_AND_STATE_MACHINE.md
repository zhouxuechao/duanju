# 03 Workflow 与状态机

## 一、项目级工作流

```text
IDEA
↓
CORE（整季核心、Story Bible、人物/地点/道具定义）
↓
OUTLINE_BATCH（分批集纲）
↓
EPISODE_SCRIPT（单集完整剧本）
↓
CHARACTER / CHARACTER_LOOK / LOCATION / PROP
↓
ASSET_IMAGE（人物、定妆、地点、道具多视图）
↓
SCENE
↓
DIRECTOR_PLAN（场景节拍与镜头骨架）
↓
SHOT_DETAIL（逐镜机位、表演、参考视角与状态增量）
↓
SHOT（服务端物化完整连续性快照）
↓
ASSET_READY
↓
STORYBOARD
↓
KEYFRAME
↓
VIDEO
↓
AUDIO
↓
TIMELINE
↓
RENDER
```

---

## 二、Shot 状态机

```text
DRAFT
↓
PLANNED
↓
STORYBOARD_GENERATING
↓
STORYBOARD_READY
↓
STORYBOARD_LOCKED
↓
KEYFRAME_GENERATING
↓
KEYFRAME_READY
↓
KEYFRAME_QC
↓
KEYFRAME_LOCKED
↓
VIDEO_GENERATING
↓
VIDEO_READY
↓
VIDEO_QC
↓
VIDEO_LOCKED
↓
AUDIO_READY
↓
EDITED
↓
FINISHED
```

异常状态：
- FAILED
- NEEDS_REPAIR
- PROVIDER_URL_EXPIRED

---

## 三、GenerationJob

状态：
- QUEUED
- RUNNING
- SUCCESS
- FAILED
- CANCELLED
- RETRY_WAIT

类型：
- STORY
- SCRIPT
- DIRECTOR_PLAN
- SHOT_DETAIL
- STORYBOARD
- KEYFRAME
- KEYFRAME_QC
- VIDEO
- VIDEO_QC
- TTS
- LIPSYNC
- TIMELINE
- RENDER
- ARCHIVE
- ASSET_IMAGE

旧数据库迁移中可能仍能看到 `CHARACTER_PLAN`、`CHARACTER_LOOK`、`LOCATION_LOOK`、`SHOT_PLAN` 和 `REPAIR`。它们只用于解释历史记录，当前代码不会再创建这些任务。

必须支持：
- retry
- cancel
- failure_reason
- provider_request_id
- progress
- cost
- input_snapshot
- output_snapshot

---

## 四、关键的火山短链 Workflow

正式 Keyframe 锁定以后：

```text
Seedream 5.0 生成
↓
拿到 provider_url
↓
立即创建/排队 Seedance 2.5 视频任务
↓
Seedance 请求使用 provider_url
↓
视频任务创建成功
↓
标记 handoff_status = HANDED_OFF
↓
随后再异步保存 archive_url
```

注意：

归档任务不能阻塞：
`Seedream → Seedance`

推荐代码逻辑：

```java
ImageResult image = seedream.generate(request);

Keyframe keyframe = saveProviderResult(image);

videoJobQueue.enqueue(
    VideoJob.fromProviderUrl(keyframe.getProviderUrl())
);

archiveQueue.enqueue(
    ArchiveJob.forKeyframe(keyframe.getId())
);
```

而不是：

```java
ImageResult image = seedream.generate(request);
String local = download(image.getUrl());
String oss = upload(local);
seedance.generate(oss); // 禁止
```

---

## 五、URL 过期策略

如果：
- Keyframe 已锁定
- provider_url 已过期
- 用户还没生成视频

不要使用 archive_url 顶替。

策略：

1. 重新发起 Seedream Keyframe 任务。
2. 尽量使用同一 prompt version / reference / seed / parameters。
3. 得到新的 provider_url。
4. 直接交给 Seedance。
5. 重新做 Keyframe QC，必要时人工确认。

---

## 六、失败恢复

任何 Shot 失败：
- 不影响其他 Scene。
- 不影响整集。
- 允许单独重画 Keyframe。
- 允许单独重拍 Video Take。
- 允许只重做 TTS。
- 允许只重渲染 Timeline。
