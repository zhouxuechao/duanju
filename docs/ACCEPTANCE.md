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
| Runtime Skill | DONE | Premise、Story Quality 等运行时 Skill 已打入最终 JAR，并由 `RuntimeSkillResourceTest` 从 classpath 读取 |
| Premise Gate | DONE | `viable=false` 停在 `PREMISE_REVIEW_REQUIRED`；编辑、接受建议、强制继续及审计字段均有集成测试 |
| PipelineRun / Resume | DONE | FREE 模式调用现有 Story、导演、媒体、QC、后期服务，按首个未完成阶段恢复 |
| Skill Manifest / Catalog | DONE | Manifest 驱动生成，生成器不覆盖人工维护 Prompt；源码和 runtime catalog 指纹一致 |
| PowerShell 脚本 | DONE | `LASTEXITCODE` 只检查刚执行的外部命令，并有静态回归测试 |
| TestBudgetGuard | DONE | 幂等命中先于预算预留；LLM、IMAGE、VIDEO、TTS、LIPSYNC 分账；uncertain 保留预留并等待 reconcile |
| FREE E2E | PASS | Golden fixture 从项目执行到可播放 `preview.mp4` / `final.mp4`，包含 QC 返修、恢复、字幕、BGM、SFX 与 FFmpeg 探测 |
| Media E2E | PASS | 真实 PNG、MP4、WAV 媒体夹具经 FFmpeg/ffprobe 完成后期链路 |
| Provider Replay | PASS | Seedream、Seedance submit/poll、TTS 脱敏历史合同回放通过 |
| 后端全量测试 | PASS | 315/315，0 failed，0 skipped |
| 前端测试与构建 | PASS | Vitest 6/6；Vite production build 通过 |

真实两镜 Canary 已执行 1 次 Seedream 和 2 次 Seedance：首镜使用 `FIRST_FRAME`，第二镜使用 `FULL_MODAL_REFERENCE`，`previousTakeId=cgt-20260921180417-czf4s`，计划与估算实际成本均为 3.2 元，重复浪费成本为 0。人物脸、灰发、深蓝棉袄、站位、右手持铃、铃铛缺口与动作相位跨镜连续；首锚图把供桌放在门前，违反相对墙面约束，因此技术与连续性通过，场景拓扑和故事准确性未通过，最终决定为 `REANCHOR`，没有把 HTTP 200 当作画质通过。

24 秒真实项目 `会发芽的欠条·真实全链路-0921-1622` 已完成故事、剧本、3 个人物、4 套定妆、3 个地点、3 个道具和 40 个已审核多视图。导演总规划沿用已付费响应完成本地复核，生成 12 个镜头骨架，服务端把镜头时长精确分配为 24 秒。第 1 个逐镜详情请求在服务商接单前被 `AccountOverdueError` 拒绝，两次请求 ID 分别为 `0217899895234839ee13f605c3786670e0524dbc441cb77a64d47`、`0217899896280663668eba1a5ab869c4433ad7bc82b08d379db92`；均无输出、无 usage、无不确定提交。真实项目保留在此断点，未继续发送图片、视频或语音付费请求。
