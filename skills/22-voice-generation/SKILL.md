# 22-voice-generation

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：用 speechText 和声音档案生成对白音频

## 输入

- `speechText`
- `voiceProfile`

## 输出

- `audioClip`

## 硬规则

- 字幕始终使用 displayText
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
