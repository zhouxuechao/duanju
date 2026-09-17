# 16-video-strategy

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：选择首帧、参考视频或多模态生成策略

## 输入

- `shot`
- `keyframe`

## 输出

- `strategy`
- `reasons`

## 硬规则

- 正式关键帧只能作为视频首帧，不作为普通参考图重复传入
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
