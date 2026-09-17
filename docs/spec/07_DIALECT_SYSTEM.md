# 07 方言系统

## 一、核心目标

口播：
- 耒阳方言 / 本地方言

字幕：
- 普通话规范文字

因为通用模型不真正理解目标方言，所以使用：
- 方言知识库
- 谐音字 / 音节
- TTS 调音

---

## 二、三文本轨

每句对白：

```text
displayText
dialectText
speechText
```

### displayText
字幕用：
`你今天跑哪里去了？`

### dialectText
真实本地方言表达。

### speechText
给 TTS 的谐音文本。

字幕永远不能取 speechText。

---

## 三、Dialogue 数据结构

```json
{
  "dialogueId": "DLG_001",
  "characterId": "WANG_DEFU",

  "displayText": "奇怪，这下面怎么有块红布？",
  "dialectText": "...",
  "speechText": "...",

  "dialect": "LEIYANG",
  "dialectStrength": 1.0,

  "emotion": "CONFUSED",
  "intensity": 0.55,
  "speed": 0.9,

  "voiceId": "VOICE_WD_01"
}
```

---

## 四、Dialect Knowledge Base

至少：
- dialect_dictionary
- dialect_phrase
- dialect_example
- dialect_correction

每次人工修正保存：

```text
普通话
→ 方言真实表达
→ TTS 最佳谐音
```

以后处理新句子：
1. 语义检索相似句。
2. Top-K 例句喂给豆包。
3. 豆包只根据已有规则组合。
4. 不允许自由编造。
5. 低置信度人工确认。
6. 修正结果继续沉淀。

---

## 五、角色方言强度

不同角色可保存：
- 1.0：强方言
- 0.5：普通话夹方言
- 0.0：普通话

---

## 六、字幕时间

```text
speechText → TTS → audio duration
displayText → subtitle
```

用音频时长做字幕 timing。

不要求口播文本和字幕逐字相同。
