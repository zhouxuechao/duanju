# 拾光 · AI 短剧工作室

依据 [V2 开发清单](docs/spec/09_MILESTONES.md) 从零实现。按照清单先完成 P0/P1 后端和 `Seedream 原始链接 → Seedance` 链路，再扩展导演、连续性、方言和剪辑。清单原文保存在 `docs/spec/`，不将文档里的启动提示词当作额外用户授权。

采用 **Java 21 + Spring Boot 3.5.16 单模块**，按业务包隔离。个人项目无需多模块发布、跨服务网络和额外调度平台。数据库是任务和素材记录的事实来源；Redis 用于通知；文件存储可选本地或 S3/MinIO。

## 启动

需要 Java 21、Maven 3.6.3+。Windows 启动脚本会优先使用 `JAVA_HOME`，也兼容本机已安装的 IDEA Java 21。不会修改全局 Java 设置。

```powershell
# 无需密钥、PostgreSQL 或 Redis；H2 数据持久化在 data/。
./scripts/run.ps1 -Mode Demo -Build

# 使用现有配置文件，只加载到当前进程，不复制或输出密钥。
./scripts/run.ps1 -Mode Live -EnvFile D:\project\aimanju\.env

# 批量验证代码。
./scripts/test.ps1
```

服务默认只监听 `http://127.0.0.1:8080`。状态接口：`GET /api/system`；健康接口：`GET /actuator/health`。同一应用提供 Vue 四工作区：创作、剧组、导演台和剪辑台。

**Demo 是流程模拟**：图片和视频均有明确演示标记，不能用来证明真实人物一致性或真实模型调用成功。Live 使用真实火山模型，会产生账号费用；只有显式选择 Live 才会调用。

生产数据库：复制 `.env.example` 为本地 `.env` 并设置数据库密码，然后运行 `docker compose up -d postgres redis`，再启动 `./scripts/run.ps1 -Mode Prod -EnvFile .env`。可选 MinIO 使用 `docker compose --profile s3 up -d`，需设置存储凭据并创建 `drama` 桶。S3 文件通过本服务读取，无需公开桶。

## 必须保持的生产规则

- Keyframe 同时保存 `providerUrl` 和 `archiveUrl`，两者用途严格分开。
- Seedance 请求中的首帧逐字符使用 Seedream 返回的原始 `providerUrl`，包括签名和查询参数；请求构建过程不下载、不压缩、不上传、不改写。
- Keyframe 通过质检并锁定后才能创建视频任务。归档必须等待服务商确认视频任务创建成功。
- 链接过期时用原提示词、参考图、seed 和参数生成**新关键帧版本**，重新质检。不能用归档地址补位。
- 图片生成后会记录提示词版本、原始引用、模型请求号、任务快照和生成版本。视频 Take 永久保存首帧链接快照。
- 网络超时或进程中断后，若无法判断服务商是否接单，任务标记为待核对，禁止自动重复提交；已有视频任务号则继续查询。
- 字幕使用 `displayText`，TTS 使用 `speechText`，真实方言表达独立保存为 `dialectText`。

## 开发入口

- [数据库与领域模型](docs/DATABASE.md)
- [模型接入与官方接口依据](docs/PROVIDER_API.md)
- [生产规则接口](docs/PRODUCTION_LOGIC.md)
- [API 使用步骤](docs/API.md)
- [当前验收记录](docs/ACCEPTANCE.md)
- [原始开发清单](docs/spec/09_MILESTONES.md)

生产技能包位于 `skills/`。运行 `python scripts/generate_skill_pack.py` 可按清单确定性重建，运行 `python scripts/validate_skill_pack.py` 校验四个 Agent、27 个 Skill 及其说明、提示词、契约、示例和用例。

包结构：`domain` 领域契约，`persistence` 存储，`workflow` 工作流，`job` 持久任务与恢复，`production` 导演/连续性/方言/时间线规则，`model` 模型接口，`provider.volcengine` 火山适配器，`storage` 归档，`api` HTTP 与 SSE。

项目不包含会员、支付、团队或社区功能。所有密钥、数据和本机工具目录均被 `.gitignore` 排除。
