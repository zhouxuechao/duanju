# AI 短剧个人生产系统 V2 开发文档

## 项目定位

这是一个**个人自用**的 AI 短剧生产系统，不做商业 SaaS，不做会员、支付、团队、社区、模板市场等功能。

目标：

> 输入一个想法，系统通过少量确认，自动完成故事、剧本、角色、场景、分镜、关键帧、视频、方言配音、普通话字幕、音效、剪辑和成片。

核心要求：

1. 操作简单。
2. 底层生产链完整。
3. 视频前后连贯。
4. 人物身份稳定。
5. 优先使用火山体系。
6. 支持“方言口播 + 普通话字幕”。
7. 所有失败都可以局部重做，不重跑整集。
8. 不追求一开始完全一键生成，先追求高质量、稳定、可控。

---

## 固定模型方案

- 文本 / 剧本 / 导演 / Prompt / QC：豆包 Seed 2.1
- 分镜 / 关键帧 / 图片：Seedream 5.0
- 视频：Seedance 2.5
- 人物身份：火山虚拟人物
- 最终渲染：FFmpeg
- 方言：自建 Dialect Engine + TTS

---

## 最重要的火山链路约束

这是旧系统中已经验证过的实际经验，必须作为新系统 P0 规则：

> Seedream 5.0 生成出来的关键帧，如果要继续给 Seedance 2.5 生成视频，不能先下载、落盘、转存 OSS 后再作为普通图片重新传入。

正确做法：

```text
Seedream 5.0
    ↓
返回火山原始生成结果 / 原始临时 URL
    ↓
直接传给 Seedance 2.5
    ↓
生成视频
```

允许另外保存一份归档副本，但：

```text
archiveUrl 只用于展示 / 归档
providerUrl 才用于 Seedance 生产
```

严禁：

```text
Seedream → 下载 JPG → 上传 OSS → Seedance
```

这条规则必须贯穿：
- Keyframe 数据结构
- Job 系统
- 火山 Adapter
- 视频任务创建
- 重拍
- URL 过期处理
- 归档策略

---

## 推荐阅读顺序

1. `01_PRODUCT_AND_ARCHITECTURE.md`
2. `02_DOMAIN_MODEL_AND_DATABASE.md`
3. `03_WORKFLOW_AND_STATE_MACHINE.md`
4. `04_VOLCENGINE_PIPELINE.md`
5. `05_AGENT_AND_SKILL_DESIGN.md`
6. `06_CONTINUITY_ENGINE.md`
7. `07_DIALECT_SYSTEM.md`
8. `08_FRONTEND.md`
9. `09_MILESTONES.md`
10. `CODEX_START_PROMPT.txt`
