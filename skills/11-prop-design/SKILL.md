# 11-prop-design

- 负责 Agent：StoryAgent
- 版本：2.0.0
- 目标：锁定道具形制、比例、磨损与四视角参考图

## 输入

- 已确认故事核心中的 prop、propBible、sourceSnapshot、referenceViewIds、单一 view

## 输出

- 一张独立参考图，按 FRONT/SIDE/BACK/SCALE 版本化

## 硬规则

- 四个视角共享轮廓、长宽高比例、材质、纹理、印记、磨损和当前状态
- SCALE 必须展示完整物件和无文字的一米比例杆；禁止人物手和第二件道具
- 图像提示由 AssetViewService 加载本技能，要求单件单视角画面，不返回 JSON

## 失败处理

每张图单独审查确认，失败只重试当前视图；道具设定或状态改版会让相关镜头参考失效。
