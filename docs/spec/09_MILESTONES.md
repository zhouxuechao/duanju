# 09 开发顺序与里程碑

## P0 地基

- [ ] Spring Boot 项目
- [ ] PostgreSQL
- [ ] Redis
- [ ] 文件存储
- [ ] Project / Episode / Scene / Shot
- [ ] Generation Job
- [ ] Prompt Version
- [ ] State Machine
- [ ] Model Adapter

---

## P1 火山人物与图片链

- [ ] 火山虚拟人物选择/绑定
- [ ] providerAssetId 存储
- [ ] providerStatus
- [ ] Character Bible
- [ ] Location Bible
- [ ] Prop
- [ ] Seedream 5.0 Adapter
- [ ] provider_url / archive_url 双 URL
- [ ] Seedream → Seedance 直接 Handoff 验证

### P1 验收
一个火山虚拟人物：
- 生成一个 Seedream Keyframe
- 不落盘
- 原始结果直接进入 Seedance
- 视频成功生成

这是最先必须通过的链路测试。

---

## P2 Director + Continuity

- [ ] Story Agent
- [ ] Director Agent
- [ ] Shot Schema
- [ ] 2~5 秒原子镜头规则
- [ ] relationToPrevious
- [ ] Scene State
- [ ] startState/endState
- [ ] Continuity Engine

---

## P3 Storyboard + Keyframe

- [ ] Storyboard Skill
- [ ] Storyboard UI
- [ ] Image Prompt Compiler
- [ ] 正式 Keyframe
- [ ] Keyframe QC
- [ ] Keyframe Lock
- [ ] provider_url 过期处理

---

## P4 Video

- [ ] Video Strategy Router
- [ ] Video Prompt Compiler
- [ ] Seedance 2.5 Adapter
- [ ] Multi-Take
- [ ] Video QC
- [ ] Repair / Reshoot
- [ ] Take Lock

---

## P5 方言

- [ ] displayText
- [ ] dialectText
- [ ] speechText
- [ ] Dialect Knowledge Base
- [ ] 人工修正
- [ ] TTS
- [ ] Voice Profile
- [ ] Subtitle
- [ ] LipSync

---

## P6 剪辑

- [ ] Sound Design
- [ ] Timeline
- [ ] FFmpeg
- [ ] SFX
- [ ] BGM
- [ ] Subtitle burn-in / sidecar
- [ ] Preview render
- [ ] Final render

---

## Benchmark 1

目标：
- 1 角色
- 1 场景
- 6 镜头
- 15~20 秒

验收：
- 人脸基本一致
- 服装一致
- 场景稳定
- 道具不乱
- 视频剪起来连贯
- 火山人物链路无授权问题
- 方言口播正常
- 普通话字幕正常

通过后：
20 秒 → 60 秒 → 90 秒 → 完整单集 → 多集
