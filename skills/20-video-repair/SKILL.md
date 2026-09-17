# 20-video-repair

- 负责 Agent：`ContinuityQcAgent`
- 版本：`1.0.0`
- 目标：把失败诊断转为局部重拍方案

## 输入

- `videoTake`
- `risks`

## 输出

- `repairPlan`
- `revisedShot`

## 硬规则

- 只重做失败镜头并保留旧版本
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
