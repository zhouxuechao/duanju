# 10-location-design

- 负责 Agent：StoryAgent
- 版本：2.2.0
- 目标：锁定地点世界坐标与四视角参考图

## 输入

- 已确认故事核心中的 location、locationBible、sourceSnapshot、referenceViewIds、单一 view

## 输出

- 一张独立参考图，按 LAYOUT/FRONT/REVERSE/SIDE 版本化

## 硬规则

- locationBible 必须提供 coordinateSystem、dimensions、surfaces、fixedFeatures、spatialRelations、lightSources、visualInvariants、prohibitedElements；缺少任何一项都不得调用图片模型
- 每个固定设施绑定唯一承载面，并记录同面、对立面、相邻面、前后层级和包含关系；禁止用没有世界坐标基准的画面左/右代替方位
- 所有视角共享同一入口、出口、门窗、道路和固定地标位置；换机位只能改变投影和可见性，不能改变空间拓扑
- LAYOUT 为正上方俯视平面；其它视角只移动相机，不镜像或旋转空间
- 看不清或不在视锥内的设施应留在画外，不能为了同时展示参照物而移面、复制、合并或改变开合状态
- 场景参考图必须传递背景拓扑、固定设施、材质与光源锚点；不得沿用人物/道具参考图中的“不要继承背景”绑定
- 图像提示由 AssetViewService 加载本技能，要求空场景单视角画面，不返回 JSON

## 失败处理

每张图单独审查确认，失败只重试当前视图；地点设定改版会让相关镜头参考失效。
