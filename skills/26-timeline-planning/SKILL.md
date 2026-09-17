# 26-timeline-planning

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：组装连续视频、对白、音效和字幕轨

## 输入

- `episode`
- `lockedAssets`

## 输出

- `timeline`
- `items`

## 硬规则

- 只允许已质检、已锁定、已归档素材进入时间线
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
