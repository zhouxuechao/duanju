# 数据库与持久化

系统以 PostgreSQL 为生产事实来源，开发与测试使用 H2 的 PostgreSQL 兼容模式。Flyway 迁移位于 `src/main/resources/db/migration/common`，同一套迁移创建 29 类 JSON 文档表；每条记录仍保留独立 `id`、`project_id`、父级 ID、`revision`、创建和更新时间，便于事务锁、版本冲突检测和按项目查询。

## 核心关系

`Project → Episode → Scene → Shot` 构成生产树。人物、定妆、地点、地点定妆、道具和道具状态由 Shot 通过 UUID 引用。Storyboard、Keyframe、VideoTake、DialogueLine 是 Shot 的版本化产出；AudioClip 关联 DialogueLine；Timeline 和 TimelineItem 只引用已采用素材。

GenerationJob 保存输入快照、状态历史、幂等键、重试次数、服务商任务 ID、结果快照和错误信息。PromptVersion 独立保存用途、编译器版本、提示词、引用和参数，重画或重拍会产生新版本，不覆盖旧记录。

## 并发与恢复

更新请求必须带当前 `revision`。服务端在事务中锁定记录并递增版本，陈旧更新返回冲突。任务提交前持久化，服务商返回后记录 request/task ID；进程恢复时只继续轮询已知异步视频任务，无法确认是否已提交的计费请求标为需要人工核对，避免重复扣费。

Redis 只用于通知扩展，不是状态事实来源。当前 SSE 在数据库事务提交后发布任务变化，因此刷新或重启仍能从数据库恢复完整状态。

## 媒体字段

Keyframe 同时保存 `providerUrl` 和 `archiveUrl`。`providerUrl` 是 Seedream 返回的原始临时链接，只能原样交给 Seedance；`archiveUrl` 只供本地预览和最终剪辑。VideoTake、AudioClip 和渲染结果同样保留服务商来源与归档来源，时间线只读取已锁定的归档副本。
