import { describe, expect, it } from 'vitest'
import {
  canAcceptObservedDeviation, canConfirmStory, canResumePipeline, keyframePrimaryAction,
  creativeQaCanRelease, pipelineStageLabel, premiseReviewActions, reconciliationChoices,
  storyboardPrimaryAction, timelinePreviewLabel, directorStageForScene, productionDashboard, taskRuntimeLabel
} from './ui-policy.js'

describe('production UI gates', () => {
  it('shows scene planning, detail progress, and the failed batch on the director desk', () => {
    const root = { id: 'plan-1', type: 'DIRECTOR_PLAN', status: 'SUCCESS', inputSnapshot: { scene: { id: 'scene-1' } }, outputSnapshot: { batchCount: 2 } }
    const first = { type: 'SHOT_DETAIL', status: 'SUCCESS', inputSnapshot: { rootPlanJobId: 'plan-1', batchIndex: 1 } }
    const second = { type: 'SHOT_DETAIL', status: 'FAILED', failureReason: '镜头状态冲突', inputSnapshot: { rootPlanJobId: 'plan-1', batchIndex: 2 } }
    expect(directorStageForScene('scene-1', [second, first, root])).toMatchObject({ label: '镜头详情第 2/2 批失败', progress: 50, failureReason: '镜头状态冲突' })
    expect(directorStageForScene('other-scene', [second, first, root])).toMatchObject({ label: '尚未拆镜', progress: 0 })
    expect(directorStageForScene('scene-1', [{ ...root, status: 'RUNNING' }])).toMatchObject({ label: '剧情节拍生成中' })
  })
  it('offers all audited premise review choices only at the premise gate', () => {
    expect(premiseReviewActions({ reviewStatus: 'PREMISE_REVIEW_REQUIRED' }))
      .toEqual(['EDIT_IDEA', 'ACCEPT_RECOMMENDATIONS', 'FORCE_CONTINUE'])
    expect(premiseReviewActions({ reviewStatus: 'REVIEW' })).toEqual([])
  })

  it('blocks story confirmation when QA has blocking issues', () => {
    expect(canConfirmStory({ reviewStatus: 'REVIEW', storyQa: { passed: false, blockingIssues: ['未来信息泄漏'] } })).toBe(false)
    expect(canConfirmStory({ reviewStatus: 'REVIEW', storyQa: { passed: true, blockingIssues: [] } })).toBe(true)
    expect(canConfirmStory({ reviewStatus: 'REVIEW', premiseAnalysis: { viable: false }, premiseAcceptance: { acceptedBy: 'USER' }, storyQa: { passed: true } })).toBe(true)
  })

  it('exposes keyframe lock as the transition that starts video', () => {
    expect(storyboardPrimaryAction({ qcStatus: 'PASSED', locked: false })).toBe('LOCK_AND_GENERATE_KEYFRAME')
    expect(storyboardPrimaryAction({ qcStatus: 'PENDING', locked: false })).toBe('NONE')
    expect(keyframePrimaryAction({ qcStatus: 'PASSED', locked: false })).toBe('LOCK_AND_GENERATE_VIDEO')
    expect(keyframePrimaryAction({ qcStatus: 'PENDING', locked: false })).toBe('NONE')
  })

  it('releases a final only after creative QA passes without blocking findings', () => {
    expect(creativeQaCanRelease({ passed: true, BLOCKING: [] })).toBe(true)
    expect(creativeQaCanRelease({ passed: true, BLOCKING: [{ code: 'IDENTITY_DRIFT' }] })).toBe(false)
    expect(creativeQaCanRelease({ passed: false, BLOCKING: [] })).toBe(false)
  })

  it('shows provider reconciliation only for uncertain submissions', () => {
    expect(reconciliationChoices({ type: 'VIDEO', submissionUncertain: true })).toContain('CONFIRMED_SUBMITTED')
    expect(reconciliationChoices({ type: 'IMAGE', submissionUncertain: true })).not.toContain('CONFIRMED_SUBMITTED')
    expect(reconciliationChoices({ type: 'VIDEO', status: 'UNKNOWN', reconciliationRequired: true, providerTaskId: 'cgt-1' }))
      .toEqual(['CONFIRMED_SUBMITTED', 'UNRESOLVED'])
    expect(reconciliationChoices({ type: 'VIDEO', submissionUncertain: false })).toEqual([])
  })

  it('allows a human to accept a recorded observed deviation', () => {
    expect(canAcceptObservedDeviation({ providerStatus: 'SUCCEEDED', locked: false, observedState: {}, observedDifferences: ['position'] })).toBe(true)
    expect(canAcceptObservedDeviation({ providerStatus: 'SUCCEEDED', locked: false, observedState: {}, observedDifferences: [] })).toBe(false)
  })

  it('distinguishes stale timeline previews and resumable runs', () => {
    expect(timelinePreviewLabel({ previewStale: true, previewUrl: '/old.mp4' })).toBe('生成新预览')
    expect(timelinePreviewLabel({ previewStale: false, previewUrl: '/current.mp4' })).toBe('重新生成预览')
    expect(canResumePipeline({ id: 'run-1', status: 'WAITING', resumeFromStage: 'VIDEO' })).toBe(true)
    expect(canResumePipeline({ id: 'run-1', status: 'SUCCESS' })).toBe(false)
  })

  it('presents canonical pipeline stages in production language', () => {
    expect(['STORY','DIRECTOR','ASSET','KEYFRAME','VIDEO','AUDIO','TIMELINE','PREVIEW','CREATIVE_QA','FINAL'].map(pipelineStageLabel))
      .toEqual(['故事','导演','素材','故事板与关键帧','视频','声音','剪辑','预览','创作质检','成片'])
  })
  it('formats the task center identity, execution phase, route, and elapsed time', () => {
    const job = { id: 'job-1', localTaskId: 'task-1', phase: 'PROVIDER_POLLING', provider: 'VOLCENGINE', model: 'seedance', startedAt: '2026-09-22T16:00:00Z' }
    expect(taskRuntimeLabel(job, new Date('2026-09-22T16:02:05Z').getTime()))
      .toBe('任务 task-1 · PROVIDER_POLLING · VOLCENGINE / seedance · 2分05秒')
  })
  it('summarizes real production resources and operational blockers', () => {
    const dashboard = productionDashboard({ project: { episodeCount: 1 }, episodes: [{ id: 'e1' }],
      'story-documents': [{ documentType: 'EPISODE_SCRIPT', episodeNo: 1, confirmed: true }],
      scenes: [{ id: 's1' }], beats: [{ id: 'b1', status: 'ACTIVE' }], shots: [{ id: 'sh1' }],
      keyframes: [{ shotId: 'sh1', selected: true, locked: true, qcStatus: 'PASSED' }],
      'video-takes': [], 'dialogue-lines': [], 'audio-clips': [], timelines: [], 'qc-results': [],
      jobs: [{ id: 'j1', type: 'VIDEO', status: 'UNKNOWN', reconciliationRequired: true }] })
    expect(dashboard.stages.map(stage => [stage.key, stage.done, stage.total])).toEqual([
      ['SCRIPT', 1, 1], ['SCENE', 1, 1], ['BEAT', 1, 1], ['SHOT', 1, 1], ['KEYFRAME', 1, 1],
      ['VIDEO', 0, 1], ['VOICE', 0, 0], ['EDIT', 0, 1], ['QC', 1, 1], ['RENDER', 0, 1]
    ])
    expect(dashboard.blockers).toEqual([{ jobId: 'j1', type: 'VIDEO', status: 'UNKNOWN', reason: '等待核对服务商状态' }])
  })
})
