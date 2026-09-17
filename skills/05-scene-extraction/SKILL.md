# 05-scene-extraction

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：从剧本提取场景和初始连续性状态

## 输入

- `script`

## 输出

- `scenes`

## 硬规则

- 同一场景只绑定一个主地点状态
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
