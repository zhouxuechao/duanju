# 19-video-qc

- 负责 Agent：`ContinuityQcAgent`
- 版本：`1.0.0`
- 目标：检查动作、身份和起止状态

## 输入

- `videoTake`
- `expectedState`

## 输出

- `passed`
- `score`
- `observedState`
- `risks`

## 硬规则

- 通过并锁定后才可进入时间线
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
