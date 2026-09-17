# 23-lipsync

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：使用已采用视频和音频生成口型同步 Take

## 输入

- `videoTake`
- `audioClip`

## 输出

- `videoTake`

## 硬规则

- 不得改变人物身份、服装、场景、动作或台词内容
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
