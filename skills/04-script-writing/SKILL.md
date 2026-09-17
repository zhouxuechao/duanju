# 04-script-writing

- 负责 Agent：`StoryAgent`
- 版本：`1.0.0`
- 目标：生成场景化剧本和三轨对白底稿

## 输入

- `episodePlan`

## 输出

- `script`
- `dialogues`

## 硬规则

- 对白保留 displayText、dialectText、speechText 三轨
- 正文必须按“场号｜内外景｜地点｜时间/天气｜出场角色与 lookId｜连续性｜目标与阻碍｜动作｜对白｜场末变化/下集交接”组织。
- 台词必须属于具体角色，有潜台词和可表演动作；方言是语言策略，不是随机口癖。
- 剧本不能只写文学梗概；每场要能直接交给导演拆成镜头，并包含人物在场、道具归属和状态变化。
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
