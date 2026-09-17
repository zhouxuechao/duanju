# 17-video-prompt-compile

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：把原子动作编译为视频模型提示词

## 输入

- `shot`
- `strategy`
- `continuity`

## 输出

- `prompt`
- `references`

## 硬规则

- 禁止把小说段落直接送入视频模型
- 视频提示词必须从结构化 Shot 与起止状态生成：起始稳定段—一次主要动作—自然收尾段，并明确机位方案、视线、道具状态和唯一主要运镜。
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
