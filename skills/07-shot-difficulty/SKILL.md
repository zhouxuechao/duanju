# 07-shot-difficulty

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：评估镜头生成难度并给出拆分建议

## 输入

- `shot`

## 输出

- `difficulty`
- `reasons`

## 硬规则

- 难度只使用 A、B、C、D
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
