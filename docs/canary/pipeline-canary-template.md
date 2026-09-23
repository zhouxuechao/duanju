# Pipeline Canary 报告模板

- 状态：`PASS / PARTIAL / FAILED`
- GenerationProfile：`TEST`
- 项目、故事与镜头数量：

## 生产链

- Story / Scene / Beat / Shot：
- Keyframes：
- Video Takes：
- Image / Video QC：
- Repair：
- Voice / TTS：
- Timeline / Preview / QA：
- Final Render：

## 连续性与人工观察

- Character consistency：
- Prop continuity：
- CONTINUOUS seam：
- Action continuity：
- Voice / Subtitle：
- Human observations：

## 付费请求与成本

逐项记录 `localJobId / taskType / model / generationProfile / providerRequestId / providerTaskId / size or resolution / requestedDuration / actualDuration / status / retryCount / billingStatus`。

- Story/Director LLM：
- Image：
- Video：
- TTS：
- VLM QC：
- Total：
- Wasted：

## 失败

- 首个失败：
- 分类：`SYSTEM_BUG / PROVIDER_CAPABILITY / MODEL_QUALITY / PROMPT_QUALITY / CONTINUITY_DATA / REFERENCE_FAILURE / NETWORK / UNKNOWN`
- 是否需要人工处理：
