# 09-character-design

- 负责 Agent：StoryAgent
- 版本：2.0.0
- 目标：锁定不可变人物身份并建立独立定妆参考图

## 输入

- 已确认故事核心中的 character 和 looks
- current core/version、sourceSnapshot、referenceViewIds、单一 view

## 输出

- 一张独立参考图，按 FRONT/LEFT/RIGHT/BACK 版本化
- 人物身份与服装定妆分层保存

## 硬规则

- 身份与定妆分开；身份主参考不能固化手持道具、场景、剧情动作或临时姿势
- 四个视角必须是同一张脸、同一体型、同一服装设定的独立图
- 图像提示由 AssetViewService 加载本技能，要求图片模型返回单视角画面，不返回 JSON

## 失败处理

每张图单独审查确认，失败只重试当前视图；身份或定妆改版会让相关镜头参考失效。
