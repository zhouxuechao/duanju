# 03-episode-planning

- 负责 Agent：`StoryAgent`
- 版本：`1.0.0`
- 目标：把故事拆为有钩子和承接的集纲

## 输入

- `storyBible`
- `characters`

## 输出

- `episodes`

## 硬规则

- 每集结尾必须给下一集可执行的承接状态
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
