import { describe, expect, it } from 'vitest'
import {
  canAcceptObservedDeviation, canConfirmStory, canResumePipeline, keyframePrimaryAction,
  premiseReviewActions, reconciliationChoices, timelinePreviewLabel
} from './ui-policy.js'

describe('production UI gates', () => {
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
    expect(keyframePrimaryAction({ qcStatus: 'PASSED', locked: false })).toBe('LOCK_AND_GENERATE_VIDEO')
    expect(keyframePrimaryAction({ qcStatus: 'PENDING', locked: false })).toBe('NONE')
  })

  it('shows provider reconciliation only for uncertain submissions', () => {
    expect(reconciliationChoices({ type: 'VIDEO', submissionUncertain: true })).toContain('CONFIRMED_SUBMITTED')
    expect(reconciliationChoices({ type: 'IMAGE', submissionUncertain: true })).not.toContain('CONFIRMED_SUBMITTED')
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
})
