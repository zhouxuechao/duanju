import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

describe('novel intelligence UI contracts', () => {
  it('shows estimate, durable progress, results and entity review before analysis', () => {
    const source=readFileSync(new URL('./components/NovelAnalysis.vue',import.meta.url),'utf8')
    for (const text of ['estimatedTotalRequests','chunkRequests','chapterRequests','arcRequests','globalGraphRequests','novel-intelligence-runs','需要人工确认的实体','确认预算并开始分析']) expect(source).toContain(text)
  })
  it('offers editable plan settings and episode-by-episode batch generation', () => {
    const source=readFileSync(new URL('./components/AdaptationPlanner.vue',import.meta.url),'utf8')
    for (const text of ['platform','region','compressionLevel','scripts/estimate','ALL_UNSTARTED','生成所选范围','生成全部未生成集','只生成本集待审剧本']) expect(source).toContain(text)
  })
  it('shows bounded source ranges instead of the whole novel', () => {
    const source=readFileSync(new URL('./components/ScriptWorkspace.vue',import.meta.url),'utf8')
    expect(source).toContain('sourceStart').toContain('sourceEnd').not.toContain('wholeNovelText')
    expect(source).toContain('v-model="line.semanticText"').toContain('v-model="line.spokenText"').toContain('v-model="line.subtitleText"')
  })
})
