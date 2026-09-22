# 24-subtitle-generation

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：按音频时长生成普通话字幕

## 输入

- `dialogues`

## 输出

- `srt`
- `cues`

## 硬规则

- 字幕文本只读取 subtitleText，不得从 semanticText 或 spokenText 推断
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
