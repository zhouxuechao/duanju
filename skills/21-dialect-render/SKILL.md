# 21-dialect-render

- 负责 Agent：`EditingAgent`
- 版本：`1.0.0`
- 目标：基于知识库生成人工可修正的方言文本

## 输入

- `displayText`
- `knowledgeBase`

## 输出

- `dialectText`
- `speechText`
- `confidence`

## 硬规则

- 知识库无可靠匹配时必须请求人工修正，不得臆造方言
- 每次运行记录 projectId、projectVersion、输入版本和产出版本，旧产出不得覆盖。

## 失败处理

返回结构化 warnings 或 FAILED；可修复失败只创建局部新版本，不改写已锁定资产。
