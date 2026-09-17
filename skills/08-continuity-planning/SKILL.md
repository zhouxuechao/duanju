# 08-continuity-planning

- 负责 Agent：`ContinuityQcAgent`
- 版本：`1.0.0`
- 目标：继承并验证镜头起止状态

## 输入

- `shot`
- `sceneState`

## 输出

- `startState`
- `endState`
- `risks`

## 硬规则

- 只有已质检并锁定的 Take 才能推进 Scene State
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
