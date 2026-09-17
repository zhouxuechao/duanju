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
