# 生产规则

## 四个 Agent 与技能

系统只划分 StoryAgent、DirectorAgent、ContinuityQcAgent、EditingAgent 四个职责边界。`skills/` 包含清单要求的 27 个生产 Skill。导演的执行契约由 Java `DirectorContract` 按当前资产生成，回归样例在 `DirectorContractTest`；其余静态技能文件可运行 `python scripts/validate_skill_pack.py` 检查结构。`GET /api/skills` 返回随应用打包的版本目录；导演契约通过 `POST /api/schemas/agents/director` 提交上下文获取。

## 镜头与连续性

镜头执行契约仅有 `src/main/java/com/yourapp/drama/production/DirectorContract.java` 一份；衔接类型从 `ShotRelation` 枚举生成。正式镜头限制为 2–5 秒、一个主要动作、一个视觉重点和最多一种主要运镜，完整机位、按角色绑定的定妆、道具归属及参考视角均须校验。模型输出本镜对白内容与剧本原文，系统保存 `DIALOGUE_LINE` 并生成真实 `dialogueIds`。Continuity Engine 从上一锁定镜头继承场景状态，只在 Take 质检通过并锁定后推进。

提示词编译器独立于导演：结构化 Shot 与人物、定妆、地点、道具和连续性状态编译为模型提示词。图片最多携带当前可见人物身份引用以及一个定妆或地点参考；视频把正式关键帧放在首帧槽位，不能同时把它作为普通参考图重复提交。

## 图片到视频的硬门槛

关键帧生成后先保存 Seedream 的原始 `providerUrl`，不下载、不改写、不用归档地址替换。人工或模型 QC 通过并锁定后，Seedance 请求直接使用该字符串。视频任务被服务商接受后才异步归档关键帧。原始链接过期时，系统要求用同一 PromptVersion 的提示词、引用、seed 和参数生成新的 Keyframe，重新 QC；归档失败不会撤销已提交的视频。

## 方言、声音和剪辑

对白保留普通话字幕 `displayText`、真实方言 `dialectText` 与 TTS 发音 `speechText`。方言知识库没有高置信匹配时必须人工修正，不能由模型臆造。TTS 使用 VoiceProfile；口型同步只接受已质检并采用的视频和已采用音频，并创建新的 VideoTake。

时间线按已锁定视频连续排列，加入对白、环境声、音效、BGM 和以音频时长计算的普通话字幕。FFmpeg 只读取已归档到本地/S3 的安全媒体，支持 SRT 边车或烧录字幕，并分别生成预览与最终版本。
