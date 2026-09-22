# V2 deletion report

本报告只记录已经完成、并确认不会破坏 API、数据库、运行时资源、反射或 Spring Bean 装配的删除。历史 Flyway migration、Provider ID/URL、Take/QC 历史和被 classpath/manifest 动态加载的 Skill 均未删除。

## 1. Python 编译缓存

Deleted:
`scripts/__pycache__/live_pipeline_check.cpython-314.pyc`

Reason:
Python 运行时缓存，不是源码或测试夹具；`git ls-files` 与全仓引用审计确认没有任何业务入口依赖该二进制内容。

Replacement:
保留 `scripts/live_pipeline_check.py` 源文件；`.gitignore` 新增 `__pycache__/` 与 `*.py[cod]`，防止缓存再次进入源码树。

Tests:
`DeadCodeContractTest#generatedPythonBytecodeCanNeverReenterTheSourceTree`

Dependency check:
无 API、DB、runtime、reflection 或 Spring Bean 依赖。

## 2. 未使用的规则来源构造方法

Deleted:
`ScreenwritingRuleResolver.addSource(ArrayNode, String, String)`

Reason:
该 private 方法没有调用点；当前来源记录已由 `RuntimeRulePackLoader.RuleFragment` 和 `RulePackBudgeter` 的实际入选片段生成，旧方法会绕过 content hash、source repo 和真实 upstream commit。

Replacement:
`ScreenwritingRuleResolver.resolve()` 遍历 `budgeted.included()`，写入 `sourceRepo`、`upstreamCommit`、`sourcePath` 和 `contentHash`。

Tests:
`ScreenwritingRuleResolverTest`、`OpenSourceAttributionContractTest`、完整编译与回归测试。

Dependency check:
private 方法；全仓搜索无调用，不是 Spring Bean、HTTP API、持久化字段或反射目标。

## 3. 未使用的篇幅 profile 重载

Deleted:
`EpisodeFormatResolver.profileId(String, int)`

Reason:
该 private 两参数重载没有调用点，而且会隐式固定 `GENERAL`，可能绕过 distribution profile。当前调用全部显式传入 distribution。

Replacement:
`EpisodeFormatResolver.profileId(String, int, String)`。

Tests:
`EpisodeFormatResolverTest`、`StoryProfilePersistenceTest`、`StoryDevelopmentIntegrationTest`。

Dependency check:
private 方法；全仓搜索无调用，不涉及 API、DB、runtime、reflection 或 Spring Bean。

## 审查后保留

- `src/main/resources/static/demo/*`：Fake Provider、离线成片测试和 FFmpeg 渲染仍直接读取，不能删除。
- `StoryDevelopmentDemo`：Mock LLM 和无付费生产验收仍使用，不能删除。
- `STORYBOARD` 资源/任务名：业务含义已由 `MediaPurpose.PREVIS` 明确，但历史 migration、数据库枚举、API 和当前可选预演链仍依赖，不能只改名删除。
- V1–V21 migration：数据库升级历史不可修改或删除。
- `DirectorContract` 的 Schema 归一化职责：仍是导演输出边界；确定性生产事实已逐步迁到 `ContinuityEngine`、`RuleEngine`、`EditorialTiming` 和 `ReactionShotRule`，剩余代码必须逐项用失败测试迁移后才能删除。

## 4. 素材与语音旁路 Prompt 构造器

Deleted:
`AssetViewService.prompt`、`AssetViewService.loadPrompt`、`AssetViewService.locationCameraInstruction`，以及 `SeedAudioVoiceGenerator` 内嵌的短剧对白业务 Prompt 拼接。

Reason:
这些代码绕过正式 `PromptCompiler → PromptIR → ProviderCompiler`，导致多视图、口型和语音任务无法按同一中间表示审计，Provider Adapter 还会理解对白业务。

Replacement:
`PromptCompiler.compileAssetReference`、`PromptCompiler.compileLipSync`、`PromptCompiler.compileVoice`，以及 `SeedreamCompiler`、`SeedanceCompiler`、`SeedAudioCompiler`。工作流只准备结构化输入并保存 PromptIR；Adapter 只发送已编译文本。

Tests:
`PromptCompilerV2Test`、`PromptBoundaryContractTest`、`AssetViewIntegrationTest`、`PostProductionIntegrationTest`、`SeedAudioVoiceGeneratorTest`、`ProviderReplayContractTest`。

Dependency check:
没有删除 Spring Bean、HTTP API、数据库字段或 migration；原多视图技能资源仍由 `PromptCompiler` 从 classpath 加载，原编译器版本号与视图失效规则保持不变。

## 5. 编剧、剧本质检与导演 Service 的 Prompt 加载器

Deleted:
`StoryDevelopmentService.prompt`、`StoryQualityService.prompt`、`DirectorGenerationService.prompt`，以及三处 Service 内的 system prompt 拼接。

Reason:
Service 同时承担业务状态和 Provider Prompt 编译，违反唯一正式入口；运行时 Skill、RulePack、阶段和 Schema 也无法用统一 PromptIR 回放。

Replacement:
`PromptCompiler.compileStory`、`PromptCompiler.compileDirector`、`StructuredPrompt` 与 `ArkStructuredTextCompiler`。Service 仍决定业务输入和 Schema，只消费编译结果。

Tests:
`PromptCompilerV2Test#directorAndStoryStructuredRequestsUseTheSameAuditedCompilerEntry`、`PromptBoundaryContractTest`、`DirectorBatchRecoveryIntegrationTest`、`ShotPlanVersionIntegrationTest`、`StoryDevelopmentIntegrationTest`、`StoryQualityPolicyTest`。

Dependency check:
Skill 路径、RulePack 指纹、JSON Schema、Gateway、任务状态和 Provider ID 均保留；只移动文本编译职责，没有删除 API、数据库字段、migration 或 Spring Bean。
