# Live Canary

`run-provider-canary.ps1` 与 `run-pipeline-canary.ps1` 默认只输出 `target/canary` 下的 Dry Run，不调用真实服务。

Phase A 需要显式 `-ConfirmLive` 或本机进程变量 `RUN_LIVE_PROVIDER_CANARY=true`。它固定执行一次 Seedream 图片请求和一次 Seedance 视频请求，完成后停止，不会启动 Phase B。

Phase A 在 `target/live-canary/provider-canary-state.json` 建立持久化运行锁。无论成功、失败还是提交状态未知，下一次真实运行都会被拦截；只有核对服务商记录并归档本次报告后，才能由人工清理该状态文件。程序不会自动消费第二次机会。

Phase B 使用独立入口，并同时要求 `RUN_LIVE_PROVIDER_CANARY=true`、`RUN_LIVE_PIPELINE_CANARY=true` 以及 Phase A 对 `2K/9:16`、`480p/5s` 的真实能力证据。当前任务按成本保护要求只完成 Phase B Dry Run 与入口隔离；必须审查 Phase A 报告后再单独启动后续生产验收。

真实密钥只允许放在本机环境变量或被 Git 忽略的本地 `.env` 文件。报告不会保存完整签名媒体 URL，只记录主机和 SHA-256 指纹。真实媒体保存在 `target/live-canary`，不会进入 Git。
