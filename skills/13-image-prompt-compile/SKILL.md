# 13-image-prompt-compile

- 负责 Agent：`DirectorAgent`
- 版本：`1.0.0`
- 目标：把结构化镜头编译为图片模型提示词

## 输入

- `shot`
- `anchors`

## 输出

- `prompt`
- `references`

## 硬规则

- 只带当前可见人物及最多一个定妆或地点参考
- 先从 Character/Look/Location/Prop Bible 和 Scene State 编译连续性约束，再写当前构图、动作、视线、表情与光线；不得把上一戏剧帧当作万能人物参考。
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
