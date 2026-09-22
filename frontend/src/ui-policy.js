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

export function storyboardPrimaryAction(board) {
  if (!board || board.locked || board.qcStatus !== 'PASSED') return 'NONE'
  return 'LOCK_AND_GENERATE_KEYFRAME'
}

export function creativeQaCanRelease(review) {
  return Boolean(review?.passed === true && (!Array.isArray(review.BLOCKING) || review.BLOCKING.length === 0))
}

export function reconciliationChoices(job) {
  if (job?.status === 'UNKNOWN' && job?.reconciliationRequired && ['VIDEO', 'LIPSYNC'].includes(job.type)) {
    return ['CONFIRMED_SUBMITTED', 'UNRESOLVED']
  }
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
  return ({ PREFLIGHT: '生产准备', STORY: '故事', DIRECTOR: '导演', ASSET: '素材', KEYFRAME: '故事板与关键帧', VIDEO: '视频',
    AUDIO: '声音', TIMELINE: '剪辑', PREVIEW: '预览', CREATIVE_QA: '创作质检', FINAL: '成片' })[stage] || stage
}

export function directorStageForScene(sceneId, jobs = []) {
  const root = jobs.find(job => job.type === 'DIRECTOR_PLAN' && job.inputSnapshot?.scene?.id === sceneId)
  if (!root) return { label: '尚未拆镜', progress: 0, busy: false }
  if (root.status === 'FAILED' || root.status === 'CANCELLED') {
    return { label: '剧情节拍失败', progress: root.progress || 0, failureReason: root.failureReason, job: root, busy: false }
  }
  if (root.status !== 'SUCCESS') {
    return { label: root.status === 'QUEUED' ? '剧情节拍排队中' : '剧情节拍生成中', progress: root.progress || 0, job: root, busy: true }
  }
  if (root.outputSnapshot?.detailsComplete) {
    return { label: `拆镜已完成 · ${root.outputSnapshot.finalShotCount || root.outputSnapshot.shotCount || 0} 镜`, progress: 100, job: root, busy: false }
  }
  const total = root.outputSnapshot?.batchCount || 0
  const details = jobs.filter(job => job.type === 'SHOT_DETAIL' && job.inputSnapshot?.rootPlanJobId === root.id)
  const complete = details.filter(job => job.status === 'SUCCESS').length
  const progress = total ? Math.round(complete / total * 100) : 0
  const failed = details.find(job => job.status === 'FAILED' || job.status === 'CANCELLED')
  if (failed) return { label: `镜头详情第 ${failed.inputSnapshot.batchIndex}/${total} 批失败`, progress, failureReason: failed.failureReason, job: failed, busy: false }
  const current = details.find(job => ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(job.status))
  if (current) return { label: `镜头详情第 ${current.inputSnapshot.batchIndex}/${total} 批${current.status === 'QUEUED' ? '排队中' : '生成中'}`, progress, job: current, busy: true }
  return { label: complete === total && total ? '镜头详情已完成，等待落盘' : '剧情节拍已生成，等待镜头详情', progress, job: root, busy: true }
}

export function taskRuntimeLabel(job, now = Date.now()) {
  if (!job) return ''
  const stamp = job.startedAt || job.createdAt
  const elapsed = Number.isFinite(job.elapsedMs) ? job.elapsedMs : stamp ? Math.max(0, now - new Date(stamp).getTime()) : 0
  const totalSeconds = Math.floor(elapsed / 1000), minutes = Math.floor(totalSeconds / 60), seconds = totalSeconds % 60
  const duration = minutes ? `${minutes}分${String(seconds).padStart(2, '0')}秒` : `${seconds}秒`
  return `任务 ${job.localTaskId || job.id || '—'} · ${job.phase || job.status || '—'} · ${job.provider || '—'} / ${job.model || job.plannedModel || '—'} · ${duration}`
}

export function productionDashboard(workspace = {}) {
  const list = key => (workspace?.[key] || []).filter(item => !item.stale)
  const episodes = list('episodes'), scenes = list('scenes'), beats = list('beats'), shots = list('shots')
  const keyframes = list('keyframes'), videos = list('video-takes'), lines = list('dialogue-lines'), clips = list('audio-clips'), timelines = list('timelines')
  const scripts = list('story-documents').filter(item => item.documentType === 'EPISODE_SCRIPT')
  const accepted = item => item.selected && item.locked && item.qcStatus === 'PASSED'
  const stage = (key, done, total) => ({ key, done, total, progress: total ? Math.min(100, Math.round(done / total * 100)) : 100 })
  const media = [...keyframes, ...videos]
  const stages = [
    stage('SCRIPT', scripts.filter(item => item.confirmed || item.reviewStatus === 'CONFIRMED').length, workspace?.project?.episodeCount || episodes.length),
    stage('SCENE', scenes.length, scenes.length), stage('BEAT', beats.filter(item => item.status === 'ACTIVE').length, beats.length),
    stage('SHOT', shots.length, shots.length), stage('KEYFRAME', keyframes.filter(accepted).length, shots.length),
    stage('VIDEO', videos.filter(accepted).length, shots.length), stage('VOICE', clips.filter(item => item.selected && item.locked).length, lines.length),
    stage('EDIT', timelines.filter(item => item.locked).length, episodes.length), stage('QC', media.filter(item => item.qcStatus === 'PASSED').length, media.length),
    stage('RENDER', timelines.filter(item => item.finalUrl && item.finalQaStatus === 'PASSED').length, episodes.length)
  ]
  const blockers = list('jobs').filter(job => ['FAILED', 'UNKNOWN', 'WAITING_HUMAN'].includes(job.status) || job.submissionUncertain || job.reconciliationRequired).map(job => ({
    jobId: job.localTaskId || job.id, type: job.type, status: job.status,
    reason: job.status === 'UNKNOWN' ? '等待核对服务商状态' : job.status === 'WAITING_HUMAN' ? '等待人工处理' : job.failureReason || '任务失败'
  }))
  return { stages, blockers }
}
