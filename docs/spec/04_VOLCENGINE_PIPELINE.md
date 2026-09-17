# 04 火山模型链路设计

## 一、角色身份

人物身份源：
- 火山虚拟人物

系统必须永久保存：
- provider
- sourceType
- providerAssetId
- providerStatus

不要把 Seedream 随机生成的新脸当角色身份源。

---

## 二、角色 Look

角色身份确定后，Seedream 5.0 负责：
- 当前服装
- 表情
- 姿势
- 场景
- Character Look 衍生图

但 Prompt 要强调：
- 保持原身份
- 保持脸型
- 保持年龄
- 保持核心五官比例
- 不重新设计身份

---

## 三、图片生产分两层

### Storyboard
只负责：
- 镜头构图
- 景别
- 站位
- 画面关系

### Video Keyframe
专门为视频准备：
- 动作起始状态稳定
- 人物姿势可继续运动
- 避免动作峰值
- 避免复杂扭曲姿态

---

## 四、最重要的 API 链路约束

旧系统实际已经验证：

```text
Seedream 原始结果
→ 直接 Seedance
```

比：

```text
Seedream
→ 下载
→ 保存
→ 再上传
→ Seedance
```

更适合人物授权/来源链。

因此：

### 生产字段
`provider_url`

### 归档字段
`archive_url`

Seedance 只能消费：
`provider_url`

---

## 五、Video Strategy Router

### INDEPENDENT_CUT
输入：
- Character refs
- Location refs
- Video Keyframe provider_url

适用：
- 新构图
- 反应镜头
- 插入镜头
- 普通切镜

### CONTINUATION
输入：
- 上一锁定 Take / 末帧
- Scene State
- 新动作

适用：
- 真正连续动作

### MOTION_REFERENCE
输入：
- Character
- Location
- Keyframe
- 动作参考

适用：
- 走路
- 打斗
- 舞蹈
- 大幅动作

---

## 六、Video Prompt Compiler

不要传剧情叙述。

必须输出：
- 时长
- 人物保持约束
- 0~N 秒动作分段
- 运镜
- 场景保持
- 禁止事项

示例：

```text
总时长 3 秒。

人物：
保持参考人物身份、年龄、当前服装。

0.0-0.7 秒：
保持蹲姿，身体基本稳定。

0.7-2.0 秒：
右手抬起，用手背擦一次额头。

2.0-3.0 秒：
右手自然放下，视线转向右前方。

摄影机：
稳定中近景，轻微向前推进。

保持：
田埂背景结构不变；
人物主体位置基本稳定。

禁止：
突然站起；
增加人物；
改变服装；
重新设计脸部；
多余肢体；
夸张动作。
```

---

## 七、多 Take

- A：1 Take
- B：1~2 Take
- C：2 Take
- D：3 Take

每个 Take 都保存：
- Prompt
- references
- provider request id
- cost
- QC score
- 是否采用
