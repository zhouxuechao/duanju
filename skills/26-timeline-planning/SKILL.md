# 26-timeline-planning

- 负责 Agent：`EditingAgent`
- 版本：`2.0.0`
- 目标：把选定 Take、对白和声音设计编译成唯一 Timeline，并明确记录每项剪辑决定

## 输入

- `episode`
- `lockedAssets`
- `directorPlan`
- `dialogueTimings`

## 输出

- `timeline`
- `items`
- `editOperations`

## 硬规则

- 只允许已质检、已锁定、已归档素材进入时间线
- Timeline 是最终渲染唯一真相源；不得根据 Shot 顺序在渲染阶段重新拼接。
- 每个视频项必须记录 `CUT`，发生源区间裁剪时记录 `TRIM`。
- `SHOW_REACTION` 与 `INSERT` 镜头分别记录 `REACTION_SHOT`、`INSERT_SHOT`。
- `J_CUT`、`L_CUT`、`AUDIO_BRIDGE` 必须真的跨越对应画面切点。
- `DIALOGUE_GAP` 必须记录 `gapBeforeMs`，`PAUSE` 必须记录 `pauseDurationMs`。
- `CLIP_REPLACE` 只能替换同一 Shot 的已采用 Take，并保留替换历史。
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
