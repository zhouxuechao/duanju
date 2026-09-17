# 18-video-generation

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：提交 Seedance 任务并跟踪多 Take

## 输入

- `keyframe`
- `prompt`

## 输出

- `videoTake`

## 硬规则

- Seedream 原始 providerUrl 必须原样直传 Seedance
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
