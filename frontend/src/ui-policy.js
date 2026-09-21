export function premiseReviewActions(document) {
  return document?.reviewStatus === 'PREMISE_REVIEW_REQUIRED'
    ? ['EDIT_IDEA', 'ACCEPT_RECOMMENDATIONS', 'FORCE_CONTINUE'] : []
}

export function canConfirmStory(document, dirty = false) {
  if (!document || dirty || document.stale || document.reviewStatus !== 'REVIEW') return false
  if (document.storyQa?.passed === false || document.storyQa?.blockingIssues?.length) return false
  return document.premiseAnalysis?.viable !== false || Boolean(document.premiseOverride || document.premiseAcceptance)
}

export function keyframePrimaryAction(frame) {
  if (!frame || frame.locked || frame.qcStatus !== 'PASSED') return 'NONE'
  return 'LOCK_AND_GENERATE_VIDEO'
}

export function reconciliationChoices(job) {
  if (!job?.submissionUncertain) return []
  return ['VIDEO', 'LIPSYNC'].includes(job.type)
    ? ['CONFIRMED_SUBMITTED', 'CONFIRMED_NOT_SUBMITTED', 'UNRESOLVED']
    : ['CONFIRMED_NOT_SUBMITTED', 'UNRESOLVED']
}

export function canAcceptObservedDeviation(take) {
  return Boolean(take && !take.locked && take.providerStatus === 'SUCCEEDED'
    && take.observedState && take.observedDifferences?.length)
}

export function timelinePreviewLabel(timeline) {
  return timeline?.previewStale || !timeline?.previewUrl ? '生成新预览' : '重新生成预览'
}

export function canResumePipeline(run) {
  return Boolean(run?.id && run.status === 'WAITING' && run.resumeFromStage)
}

export function pipelineStageLabel(stage) {
  return ({ PREFLIGHT: '生产准备', STORY: '故事', DIRECTOR: '导演', IMAGE: '画面', VIDEO: '视频',
    AUDIO: '声音', TIMELINE: '剪辑', PREVIEW: '预览', CREATIVE_QA: '创作质检', FINAL: '成片' })[stage] || stage
}
