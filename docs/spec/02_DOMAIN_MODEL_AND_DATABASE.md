# 02 领域模型与数据库

## 一、核心层级

```text
Project
└── Episode
    └── Scene
        └── Shot
```

Shot 是最核心对象。

---

## 二、核心表

### 项目与剧情
- `project`
- `story_bible`
- `episode`
- `scene`
- `shot`

### 资产
- `character`
- `character_provider_asset`
- `character_look`
- `location`
- `prop`
- `prop_state`

### 图片与视频
- `storyboard`
- `keyframe`
- `video_take`

### 对白与声音
- `dialogue_line`
- `voice_profile`
- `audio_clip`

### 生产系统
- `generation_job`
- `qc_result`
- `prompt_template`
- `prompt_version`
- `cost_record`

### 剪辑
- `timeline`
- `timeline_item`

---

## 三、Shot 推荐结构

```json
{
  "shotId": "EP01-SC01-SH005",
  "purpose": "老人发现地下红布",

  "duration": 2.8,

  "characterIds": ["WANG_DEFU"],
  "locationId": "RICE_FIELD",
  "propIds": ["HOE", "RED_CLOTH"],

  "shotSize": "CLOSE_UP",
  "cameraAngle": "EYE_LEVEL",
  "cameraMovement": "SLOW_PUSH_IN",

  "action": "老人动作突然停止，低头看向地面",
  "emotion": "CONFUSED",

  "dialogueIds": [],

  "startState": {},
  "endState": {},

  "relationToPrevious": "REACTION",
  "difficulty": "B",

  "status": "PLANNED"
}
```

---

## 四、镜头关系枚举

- `CONTINUOUS`
- `REVERSE_SHOT`
- `REACTION`
- `INSERT`
- `CUTAWAY`
- `ESTABLISHING`
- `MATCH_CUT`
- `TIME_JUMP`
- `LOCATION_CHANGE`

---

## 五、镜头难度

- A：空镜 / 静态
- B：微动作
- C：普通明显动作
- D：多人 / 高难动作 / 复杂道具

---

## 六、Character

```json
{
  "characterId": "WANG_DEFU",
  "name": "王德福",
  "provider": "VOLCENGINE",
  "sourceType": "PUBLIC_VIRTUAL_HUMAN",
  "providerAssetId": "asset-xxx",
  "providerStatus": "AVAILABLE",
  "identityLocked": true,
  "baseLookId": "WD_BASE"
}
```

注意：
- `providerStatus` 比 `rightsStatus=APPROVED` 更适合公共虚拟人物。
- 公共虚拟人物不是用户自己做肖像授权。

---

## 七、Keyframe 必须保存两种 URL

```text
provider_url
archive_url
```

### provider_url
火山 Seedream 原始返回结果。

用途：
- 直接传给 Seedance。
- 属于短生命周期生产引用。

### archive_url
用户自己的 OSS/MinIO 长期副本。

用途：
- 预览
- 归档
- 历史查看

禁止：
- 用 `archive_url` 替代 `provider_url` 进入 Seedance。

---

## 八、keyframe 推荐字段

```text
id
shot_id
version
provider
source_model

provider_url
provider_url_expires_at

archive_url

generation_job_id
provider_request_id

handoff_status
qc_status
selected
locked

created_at
```

`handoff_status`：
- READY
- HANDED_OFF
- EXPIRED
- INVALID

---

## 九、video_take

```text
id
shot_id
take_no
provider
model

prompt_version_id
provider_request_id

source_keyframe_id
source_provider_url_snapshot

video_url
archive_url

qc_score
selected
locked
cost
created_at
```

---

## 十、Dialogue 三文本轨

```text
display_text
dialect_text
speech_text
```

绝对不要合并。
