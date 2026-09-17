# 06 连续性引擎

## 一、目标

解决：
- 人脸跳
- 衣服跳
- 人物位置跳
- 道具消失
- 场景布局跳
- 连续动作断裂

---

## 二、Scene State

```json
{
  "time": "SUNSET",
  "lighting": "WARM",
  "characters": {
    "WANG_DEFU": {
      "lookId": "WD_FARM_01",
      "position": "FIELD_RIGHT",
      "pose": "CROUCHING",
      "lookDirection": "DOWN",
      "holding": "PIPE",
      "emotion": "TIRED"
    }
  },
  "props": {
    "HOE": {
      "position": "GROUND_LEFT",
      "holder": null
    }
  }
}
```

---

## 三、Shot 执行前

Continuity Engine 生成：
- startState
- inherited constraints
- required assets
- continuity risk score

---

## 四、Shot 执行后

根据锁定 Take：
- 检查 endState
- 更新人物状态
- 更新道具状态
- 更新场景状态
- 为下一镜准备上下文

---

## 五、relationToPrevious 决定继承强度

### CONTINUOUS
强继承：
- 姿态
- 位置
- 朝向
- 道具
- 动作末态

### REVERSE_SHOT
继承：
- 人物
- 服装
- 时间
- 光照
- 空间关系

但允许新构图。

### INSERT
主要继承：
- 当前场景
- 当前道具状态

### LOCATION_CHANGE
不强制空间继承。

---

## 六、重要原则

不要机械地：
“上一镜最后一帧永远做下一镜第一帧”。

只有真正连续动作才这样做。

影视连续性 = 状态连续 + 剪辑逻辑连续，不等于像一条长视频一样无缝。
