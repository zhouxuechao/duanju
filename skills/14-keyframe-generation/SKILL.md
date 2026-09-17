# 14-keyframe-generation

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：提交并记录正式关键帧

## 输入

- `prompt`
- `references`

## 输出

- `keyframe`

## 硬规则

- 同时保存 providerUrl 与 archiveUrl，二者用途不可互换
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
