# 06-shot-planning

- 负责 Agent：`DirectorAgent`
- 版本：`3.1.0`
- 目标：把场景拆成原子镜头

## 输入

- `scene`
- `episodeScript`
- `continuitySnapshot`
- `assets`：当前版本人物、定妆、场景、道具及已批准视图

执行契约唯一来源是 `src/main/java/com/yourapp/drama/production/DirectorContract.java`，按本次 UUID 和 `ShotRelation` 枚举动态生成并校验。`POST /api/schemas/agents/director` 接收上述上下文返回真实契约。没有独立的静态 schema 或占位示例；可执行例子和反例在 `DirectorContractTest`。

## 输出

- `sceneState`：本次物理场景唯一的 locationId、time、lighting、spatialRelations。
- `shots`：每镜 startState/endState 只输出 characters/props 增量；数据库完整状态快照由 sceneState 和服务端累计台账合成。
- 每镜 `dialogues`：来自已确认剧本的台词、角色和时间；数据库 ID 由系统生成。

## 硬规则

- 镜头 2 至 5 秒且只有一个主要动作和视觉重点
- 本次只能拆一个连续物理场景，契约不接受 TIME_JUMP 或 LOCATION_CHANGE；换时间、地点先在剧本层拆场。
- 机位必须结构化记录 position、height、distance、lensMm、horizontalAngle、verticalAngle、subjectPlacement、focusPoint、depthOfField、lightingDirection、movementPath、movementSpeed。
- cameraMovement 只允许一个主要运镜；完整机位参数不等于多个运镜。
- referenceViews 为每个可见人物当前定妆、场景、道具指定一个对应机位的已批准视图。
- 持物或局部肢体镜头必须把人物世界站位、身体在画面内/画面外的来源方向、明确的左/右身体部位、入画方向和道具接触点作为同一个几何关系设计；不能只规定道具在画面中央，让模型自行猜测手的方向和身体站位。
- 每个可见人物和道具的起终状态必须完整记录 identityId、lookId、位置、朝向、动作阶段和双向道具归属；镜头外资产可省略，由服务端连续性台账继承，重新入画时必须与台账一致，未经授权不得改变。
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

服务商返回正文与请求 ID 留在任务记录中，校验错误定位到具体镜头和字段。失败只重试本场，既不自动重试收费调用，也不改写已锁定资产。
