# 05 Agent 与 Skill 设计

## 一、只做 4 个真正 Agent

### StoryAgent
负责：
- Idea → Story Bible
- Character Plan
- Episode Plan
- Script

### DirectorAgent
负责：
- Scene → Shot
- 镜头语言
- 原子动作
- 镜头关系
- 节奏

### ContinuityQcAgent
负责：
- Scene State
- Shot continuity
- Keyframe QC
- Video QC
- Repair diagnosis

### EditingAgent
负责：
- Timeline
- Dialogue
- SFX
- BGM
- Subtitle
- 剪辑节奏

---

## 二、Skills

```text
skills/
  01-story-planning/
  02-character-planning/
  03-episode-planning/
  04-script-writing/

  05-scene-extraction/
  06-shot-planning/
  07-shot-difficulty/
  08-continuity-planning/

  09-character-design/
  10-location-design/
  11-prop-design/

  12-storyboard-generation/
  13-image-prompt-compile/
  14-keyframe-generation/
  15-keyframe-qc/

  16-video-strategy/
  17-video-prompt-compile/
  18-video-generation/
  19-video-qc/
  20-video-repair/

  21-dialect-render/
  22-voice-generation/
  23-lipsync/
  24-subtitle-generation/

  25-sound-design/
  26-timeline-planning/
  27-video-render/
```

每个 Skill：
```text
SKILL.md
prompt.md
input-schema.json
output-schema.json
examples/
tests/
```

---

## 三、Director Agent 硬规则

- 默认镜头 2~5 秒。
- 一个镜头一个主要动作。
- 一个镜头一个主要视觉重点。
- 最多一种主要运镜。
- 长对白做正反打。
- 多人复杂动作拆分。
- 使用 REACTION / INSERT / CUTAWAY 隐藏 AI 连贯性弱点。
- 不允许大段小说描述直接变成视频 Prompt。

---

## 四、Prompt Compiler 独立

禁止：
Director 直接写最终 Seedream/Seedance Prompt。

必须：
Director 输出结构化 Shot。

然后：

```text
Shot
+ Character Bible
+ Location Bible
+ Prop
+ Continuity State
+ Style Bible
→ Prompt Compiler
→ 模型专用 Prompt
```

这样以后换模型不需要重写导演逻辑。
