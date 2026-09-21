# 验收记录

此记录区分模拟链路、契约测试和真实服务商画质验收。源代码存在不等于真实模型成功；模拟成片不能证明真实人物一致性。

| 项目 | 2026-09-14 验收结果 |
| --- | --- |
| P0 Spring Boot / PostgreSQL-H2 / Redis 通知边界 / 文件存储 | 已实现；Flyway 迁移与应用启动通过 |
| 持久任务 / 状态历史 / 幂等 / 取消 / 重试 / 重启恢复 | 已通过自动化测试 |
| 火山 LLM / Seedream / Seedance / SeedAudio 适配器 | 已实现；HTTP 契约测试通过；本地 Live 配置安全加载并启动 |
| Seedream 原始 URL → Seedance 直传 | 已通过集成测试；提交视频前无下载或归档调用，URL 字符串逐字保持 |
| providerUrl / archiveUrl 隔离 | 已通过过期、归档失败和禁止回退测试 |
| 过期关键帧按原输入重画 | 已通过 PromptVersion、提示词、引用和 providerOptions 快照复用测试；新版本需重新 QC |
| P2 Director + Continuity | 已实现镜头规则、关系、难度、起止状态、Scene State 与锁定推进规则 |
| P3 Storyboard + Keyframe | 已实现生成、界面、QC、版本、锁定与局部重画 |
| P4 Video | 已实现策略路由、提示词、多 Take、QC、修复/局部重拍、锁定与取消 |
| P5 方言 | 已实现三轨对白、知识库、人工修正、VoiceProfile、TTS、字幕与 LipSync 任务 |
| P6 剪辑 | 已实现声音设计、BGM/SFX 导入、时间线、FFmpeg、边车/烧录字幕、预览/最终渲染 |
| 4 Agent / 27 Skill | 27 项均含说明、提示词、输入/输出 Schema、示例和测试用例；189 个技能产物校验通过 |
| Vue 四工作区 | 前端构建通过；创作、剧组、导演台、剪辑台均已在浏览器实际渲染 |
| 自动化测试 | 16 项通过，0 失败 |
| Benchmark 1 本地链路 | 1 人物、1 场景、6 镜头、18 秒；方言/TTS、BGM/SFX、普通话字幕和 FFmpeg 预览成片通过 |
| Benchmark 1 真实人物画质 | 待外部验收：尚缺一个账号内已授权的虚拟人物 `providerAssetId` 及对应原始参考图 URL |

真实配置来自用户指定的 `D:\\project\\aimanju\\.env`，密钥没有复制到仓库或日志。Live 启动检查没有发起模型生成，因此没有产生本次验收调用费用。

旧项目可读取的数据只有视频任务的 `lastFrameAssetId`。末帧资产既不是人物身份素材，也没有对应原始人物参考图，按连续性与授权规则不能用于冒充真实人物完成 P1/Benchmark 画质验收。取得合格人物素材后，可直接在剧组工作区绑定并执行真实 Keyframe → Seedance → QC → 后期链路。

## 2026-09-21 源码收尾与真实链路复验

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| Seedance CONTINUOUS Provider 路由 | DONE | `FIRST_FRAME`、`FULL_MODAL_REFERENCE`、`CONTINUATION_LAST_FRAME` 与能力阻断均有合同测试；真实第二镜请求包含 `reference_video`，且不含 `first_frame` |
| FULL_MODAL Keyframe 归档 | DONE | `firstFrameProviderUrl=null` 时，归档任务仍从正式 Keyframe resource 读取原始 `providerUrl`；失败测试与集成回归通过 |
| 人物四视图世界坐标 | DONE | LEFT/RIGHT 使用相反的结构化相机方位、可见脸侧和鼻尖画面方向；不对称特征绑定人物身体坐标；旧人物多视图编译器结果不能继续进入生产 |
| Runtime Skill | DONE | Premise、Story Quality 等运行时 Skill 已打入最终 JAR，并由 `RuntimeSkillResourceTest` 从 classpath 读取 |
| Premise Gate | DONE | `viable=false` 停在 `PREMISE_REVIEW_REQUIRED`；编辑、接受建议、强制继续及审计字段均有集成测试 |
| PipelineRun / Resume | DONE | FREE 模式调用现有 Story、导演、媒体、QC、后期服务，按首个未完成阶段恢复 |
| Skill Manifest / Catalog | DONE | Manifest 驱动 29 个 Skill；Validator 不再读取旧 Catalog 或硬编码数量，并验证路径、运行时打包、重复 ID/指纹；源码和 runtime catalog 指纹一致 |
| PowerShell 脚本 | DONE | `LASTEXITCODE` 只检查刚执行的外部命令，并有静态回归测试 |
| TestBudgetGuard | DONE | 幂等命中先于预算预留；LLM、IMAGE、VIDEO、TTS、LIPSYNC 分账；uncertain 保留预留并等待 reconcile |
| FREE E2E | PASS | Golden fixture 从项目执行到可播放 `preview.mp4` / `final.mp4`，包含 QC 返修、恢复、字幕、BGM、SFX 与 FFmpeg 探测 |
| Media E2E | PASS | 真实 PNG、MP4、WAV 媒体夹具经 FFmpeg/ffprobe 完成后期链路 |
| Provider Replay | PASS | Seedream、Seedance submit/poll、TTS 脱敏历史合同回放通过 |
| 场景空间拓扑源头门禁 | PASS | Story Bible 强制世界坐标、承载面、固定设施、关系、光源、不变量与禁入物；旧场景编译版本不得进入生产 |
| 后端全量测试 | PASS | 320/320，0 failed，0 skipped |
| 前端测试与构建 | PASS | Vitest 6/6；Vite production build 通过 |

真实两镜 Canary 已执行 1 次 Seedream 和 2 次 Seedance：首镜使用 `FIRST_FRAME`，第二镜使用 `FULL_MODAL_REFERENCE`，`previousTakeId=cgt-20260921180417-czf4s`，计划与估算实际成本均为 3.2 元，重复浪费成本为 0。人物脸、灰发、深蓝棉袄、站位、右手持铃、铃铛缺口与动作相位跨镜连续；首锚图把供桌放在门前，违反相对墙面约束，因此技术与连续性通过，场景拓扑和故事准确性未通过，最终决定为 `REANCHOR`，没有把 HTTP 200 当作画质通过。

本轮集成修复后的真实 Canary 复验在第一张 Seedream 图片请求处被服务商以账户欠费拒绝，请求 ID 为 `0217899913215016a861da7d15ce34477456226ae15cbba669fe3`。服务商没有返回任务 ID、媒体输出或 usage，Seedance 调用次数为 0；系统停止后续提交，没有用重复请求绕过外部故障。因此本轮真实 Canary 状态为 `BLOCKED`，此前保存的真实两镜证据仍保留，但不能替代本轮修复后的重新验收。

24 秒真实项目 `会发芽的欠条·真实全链路-0921-1622` 已完成故事、剧本、3 个人物、4 套定妆、3 个地点、3 个道具和 40 个已审核多视图。导演总规划沿用已付费响应完成本地复核，生成 12 个镜头骨架，服务端把镜头时长精确分配为 24 秒。第 1 个逐镜详情请求在服务商接单前被 `AccountOverdueError` 拒绝，两次请求 ID 分别为 `0217899895234839ee13f605c3786670e0524dbc441cb77a64d47`、`0217899896280663668eba1a5ab869c4433ad7bc82b08d379db92`；均无输出、无 usage、无不确定提交。真实项目保留在此断点，未继续发送图片、视频或语音付费请求。

## 2026-09-21 统一规则包与生产闭环终验

1. 三个固定上游提交分别为 `edd0df754320c2f3949fb198cea7847c71d0cde0`、`079df685f7cf2f0de635362bd359c233db38f9fe`、`4f318097c54a2e24ea34d3c9d23d30f5ec332f11`。
2. 固定快照位于 `skills/vendor/short-drama-factory`、`skills/vendor/manju-laoli-skill`、`skills/vendor/oiuv-ai-short-drama`，每个来源都有 `UPSTREAM.md`。
3. 运行时命名空间为 `SCREENWRITING_CORE`、`STORY_TYPE`、`STORY_PATTERN`、`DIRECTOR`、`PERFORMANCE`、`SPATIAL`、`PRODUCTION_QA`、`PROVIDER_SEEDANCE_20`、`PROVIDER_SEEDANCE_25`。
4. `RuntimeRulePackLoader` 从 classpath 读取 Markdown 正文，不使用文件名占位。
5. 规则选择示例：`REVENGE + REBIRTH` 加载 `satisfaction-model`；`SUSPENSE` 不加载；`S4 + A3 + FACS` 才加载 top-view、asset-first 与 FACS。
6. `CORE` 主编剧规则包的当前内容指纹示例为 `80f3cdfcfc2dfdeb59dc3993665421920b80a2df4c06b7ad4a2aefd7dd068f59`；正文、Profile、上游提交或编译器版本变化都会改变最终指纹。
7. `StoryBrief` 是第一份可编辑、可确认的故事文档，确认后才进入 Premise。
8. `SatisfactionEngine` 只对适用故事类型启用，普通悬疑、爱情等类型不会被爽剧规则污染。
9. `EvidenceLedger` 联通证据状态与角色知识，检测证据提前出现、销毁后复现和未来信息泄漏。
10. `RewriteBoundary` 限制局部重写的左右连续性边界。
11. `ScriptBeatExtractor` 与 `ScriptBeatCoverageValidator` 阻断漏剧情、加剧情和对白遗漏。
12. `SpatialComplexityAnalyzer` 使用 S0～S4，复杂空间按需增加空间规则。
13. `PresenceLedger` 明确在场、画外与退场人物，阻断人物凭空消失。
14. `FramingVisibilityValidator` 校验景别、主体与必须可见表演的一致性。
15. `FirstShotAnchorValidator` 阻断首镜主体和动作归属歧义。
16. `MaterialActivationPlan` 只激活当前镜头使用的参考素材。
17. `ReferenceAuthority`、`mustNotTransfer` 与 `ReferenceIndexMap` 保留素材语义和供应商索引映射。
18. Seedance 2.0/2.5 使用独立 Provider Rule/Profile；`FIRST_FRAME`、`FULL_MODAL_REFERENCE`、`CONTINUATION_LAST_FRAME` 路由互斥并有合同测试。
19. `CrossShotQc` 比较上一 Take 末态与当前 Take 首态；接受后的 `observedState` 是下一镜权威来源。
20. `ChangeImpactAnalyzer` 按 `LOCAL`、`DEPENDENT_RESOURCE`、`FULL_REVIEW` 计算复验范围。
21. Story Regression 共 10 套：现代逆袭、甜宠、悬疑、重生复仇、复杂多人空间、连续动作、24 秒 MICRO、90 秒 STANDARD、180 秒 LONG、海外 CUSTOM。
22. Director Regression 覆盖 Beat、Presence、位移、多人空间、景别、首镜锚定、动作合同、表演细节与对白时长。
23. Provider Regression 覆盖模型切换、最小素材激活、权限边界、索引、硬限制、参数分离和请求体快照。
24. FREE E2E 按 `Story → Director → Image → Video → Audio → Timeline → Preview → Creative QA → Final` 执行，使用真实 PNG/MP4/WAV 与 FFmpeg；后端 399/399、前端 7/7、生产构建全部通过。
25. 三镜头 Canary 合同已准备：Shot01=`FIRST_FRAME`，Shot02=`FULL_MODAL_REFERENCE`，Shot03=`REANCHOR_FIRST_FRAME`，成功时会保存三份真实 Request Body 和媒体。
26. 本次真实 Canary 在第一张 Seedream 图片处再次被服务商以 `AccountOverdueError` 拒绝，请求 ID `0217900033674920477e7d991a1abd211e987ddddb28caba70e00`；无任务 ID、无媒体、Seedance 请求数为 0，未重复提交。唯一外部阻断仍是当前方舟账号/资源池欠费状态。

Actual TTS reconciliation 现已把预计时长、实测时长、实际处理动作和安全倍率写入 TimelineItem；0.9～1.1 的轻微调整会编译为 FFmpeg `atempo`，可容纳的小幅时间线移动会被记录，超过安全阈值才阻断并转人工处理。普通用户界面只显示业务阶段和中文质量项，上游仓库、提交和指纹仅在 `?debug=1` 时显示。
