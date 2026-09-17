# 27-video-render

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：生成可审阅预览和最终成片

## 输入

- `timeline`

## 输出

- `render`
- `subtitle`

## 硬规则

- FFmpeg 只读取安全的本地归档路径
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
