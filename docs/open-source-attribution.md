# Open source attribution

本文件记录仓库中实际复制或改编的上游内容。运行时规则只作为创作知识输入；本项目的领域模型、状态机、任务系统、Provider 适配、质检、返修、时间线和界面均为本仓库自己的实现。

| source | repository | pinned revision | license | copied files | adapted files | purpose |
|---|---|---|---|---|---|---|
| Short Drama Factory | `https://github.com/lixiaoxiao9888-create/short-drama-factory` | `edd0df754320c2f3949fb198cea7847c71d0cde0` | MIT；完整文本保存在 `skills/vendor/short-drama-factory/LICENSE` | `skills/vendor/short-drama-factory/**` 中固定快照的编剧参考、模板、示例和校验脚本 | `RuntimeRulePackLoader` 只选择所需片段；`ScreenwritingRuleResolver` 将片段投影到本项目 `DramaRulePack`，没有复制上游工作流代码 | 人物圣经、情绪合同、连续性台账、Hook、断章、对白检查和篇幅规则 |
| Manju Laoli Skill / Short Drama Director Suite | `https://github.com/lixiaoxiao9888-create/manju-laoli-skill` | `079df685f7cf2f0de635362bd359c233db38f9fe` | MIT；完整文本保存在 `skills/vendor/manju-laoli-skill/LICENSE` 和 `DIRECTOR-LICENSE` | `skills/vendor/manju-laoli-skill/short-drama-director/references/**` 固定快照 | `RuntimeRulePackLoader` 选择导演、空间、表演和生产质检片段；本项目用自己的 Shot、Blocking、PromptIR、RuleEngine 和 Timeline 实现 | 导演语言、机位、空间拓扑、表演、衔接与素材优先规则 |
| oiuv AI Short Drama | `https://github.com/oiuv/ai-short-drama` | `4f318097c54a2e24ea34d3c9d23d30f5ec332f11` | 上游 README 声明 MIT；该固定提交根目录没有独立 LICENSE，事实与文件清单保存在 `skills/vendor/oiuv-ai-short-drama/UPSTREAM.md` | `skills/vendor/oiuv-ai-short-drama/**` 中列明的 Skill 和参考文档 | `src/main/resources/provider-rules/seedance-2.5/**` 是针对本项目 Provider 边界重新整理的紧凑规则；`RuntimeRulePackLoader` 保存来源仓库、提交和原始路径 | 故事 Brief、类型满足模型、主题/模板分析、镜头 Prompt 与 Seedance 规则 |

## 研究过但没有复制源码

以下项目只用于产品能力或架构对照，没有文件进入本仓库，因此不列为 vendored source：

- `Jellyfish / AI-SHORT-DRAMA`（Apache-2.0）：研究短剧生产模块边界。
- `yfge/ai-video-studio`（MIT）：研究 Timeline 与编辑工作流。
- `SDSmirnov/AI-Microdrama-Factory`（WTFPL）：研究微短剧生产步骤。
- LibTV：只参考公开展示的产品能力；没有复制私有、未授权或不可核验源码。

## 维护规则

1. 新增或更新上游快照前先核对目标 revision 的许可证。
2. 记录仓库、revision、复制文件、改编文件和用途，再允许规则进入运行时目录。
3. MIT/Apache 等许可证要求的版权和许可声明与快照一起保留；许可证不清楚时不得复制。
4. `RuntimeRulePackLoader` 的 `sourceRepo`、`upstreamCommit`、`sourcePath` 必须与本文件一致，生产记录继续保存规则指纹。
