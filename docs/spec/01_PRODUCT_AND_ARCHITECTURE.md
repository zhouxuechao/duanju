# 01 产品与总体架构

## 一、理想使用流程

```text
创建项目
  ↓
输入一个想法
  ↓
选择：
- 题材
- 集数
- 单集时长
- 视觉风格
- 画幅
- 方言
  ↓
AI 生成 Story Bible
  ↓
AI 生成人物与分集大纲
  ↓
确认故事
  ↓
创建/绑定火山虚拟人物
  ↓
生成角色 Look / 场景 Bible
  ↓
确认资产
  ↓
AI 拆 Scene / Shot
  ↓
生成 Storyboard
  ↓
确认 Storyboard
  ↓
生成正式 Keyframe
  ↓
Keyframe QC
  ↓
Seedream 原始结果直接交给 Seedance
  ↓
多 Take 视频生成
  ↓
Video QC
  ↓
失败镜头重拍
  ↓
方言 TTS + 普通话字幕
  ↓
音效 / BGM
  ↓
Timeline
  ↓
FFmpeg 渲染
  ↓
成片
```

---

## 二、前端只保留 4 个工作区

### 1. 创作
- 想法输入
- 题材
- 集数
- 时长
- 风格
- 方言
- Story Bible
- 分集大纲
- 剧本

### 2. 剧组
- 人物
- 火山虚拟人物绑定
- Character Look
- 场景
- 道具
- Voice Profile

### 3. 导演台
这是最核心页面：
- Scene
- Storyboard
- Shot
- Keyframe
- Take
- QC
- 重画
- 重拍
- 采用 / Lock

### 4. 剪辑台
- 大播放器
- Timeline
- 对白
- SFX
- BGM
- 字幕
- 导出

---

## 三、总体后端架构

```text
Vue 3
  ↓
Spring Boot API
  ↓
Workflow Engine
  ├── Story Agent
  ├── Director Agent
  ├── Continuity/QC Agent
  ├── Editing Agent
  │
  ├── Skills
  ├── Prompt Compiler
  ├── Continuity Engine
  ├── QC Engine
  └── Strategy Router
        ↓
Model Adapters
  ├── Doubao Seed 2.1
  ├── Seedream 5.0
  ├── Seedance 2.5
  └── TTS
        ↓
Storage / PostgreSQL / Redis
        ↓
FFmpeg Render
```

---

## 四、技术栈

- Java 21
- Spring Boot
- Spring AI（可选，仅用于模型/tool 交互，不负责整个生产编排）
- PostgreSQL
- Redis
- MinIO / OSS（归档用）
- Vue 3
- SSE 或 WebSocket
- FFmpeg

---

## 五、关键原则

1. 一切围绕 Shot。
2. 视频模型不是导演，只执行镜头。
3. 普通镜头 2~5 秒。
4. 一个镜头一个主要动作。
5. Storyboard 与 Video Keyframe 分离。
6. 人物、场景、道具资产化。
7. 连续性不是 Prompt 一句话，而是状态系统。
8. 便宜阶段先发现错误。
9. Keyframe 未通过 QC，禁止生成视频。
10. Seedream → Seedance 必须保持火山原始链路。
11. 所有生成都版本化。
12. 所有失败都局部重做。
13. 方言声音和普通话字幕彻底分轨。
