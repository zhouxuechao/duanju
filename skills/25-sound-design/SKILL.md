# 25-sound-design

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：规划环境声、音效和背景音乐

## 输入

- `timeline`
- `scenes`

## 输出

- `soundDesign`

## 硬规则

- 对白清晰度优先且音效不得替代叙事信息
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
