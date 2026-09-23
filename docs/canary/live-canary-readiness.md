# Live Canary 准备验收

状态：`PROVIDER CANARY READY`

本轮只完成真实 Canary 的安全入口、Dry Run、预算硬限制、证据快照和报告模板，没有调用真实付费 Provider。

## Phase A 计划

- `GenerationProfile=TEST`
- Seedream `doubao-seedream-5-0-260128`：1 次图片生成，`2K`，画幅意图 `9:16`
- Seedance `doubao-seedance-2-0-fast-260128`：1 次视频提交，`480p`，5 秒，无原生音频
- Seedream 原始 Provider URL 逐字符传入 Seedance `first_frame`
- 自动重试次数：0
- 真实生成请求总数：2

轮询、媒体读取和 ffprobe 不属于新的生成请求。

## 安全保护

- 默认脚本只运行 Dry Run。
- Live 必须显式使用 `-ConfirmLive` 或 `RUN_LIVE_PROVIDER_CANARY=true`。
- 图片和视频请求上限分别固定为 1。
- `TEST_MAX_COST_CNY` 会在两个计划请求之间预留，实际价格未知时仍标记 `UNPRICED`。
- `target/live-canary/provider-canary-state.json` 防止崩溃、响应丢失或人工误重跑导致重复提交。
- 存在 UNKNOWN、`submissionUncertain` 或待对账任务时拒绝启动。
- 报告不保存 API Key 或完整签名 URL。

## Evidence

只有真实探测通过的 `2K/9:16` 和 `480p/5s/first-frame` 单元会写为 `LIVE_VERIFIED`。其他尺寸、比例、分辨率、时长和参考方式继续为 `STATIC_UNVERIFIED`。

## 回归

- 前端：11/11 通过，生产构建成功。
- 后端全量：561 个测试，0 failure，0 error，3 skipped。
- 持久化 Canary 状态锁在全量测试后另行完成针对性回归。
- PostgreSQL Testcontainers 仍因本机没有 Docker 跳过。
- Live Provider：未执行。

Phase B 已有独立 Dry Run 和报告模板。它不会由 Phase A 自动触发；必须先审查 Phase A Evidence，再开启单独任务。
