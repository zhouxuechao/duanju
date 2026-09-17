<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ project: Object, workspace: Object, busy: Boolean })
const emit = defineEmits(['refresh', 'message'])

const kind = ref('CHARACTER_LOOK')
const selectedAssetId = ref('')
const detailId = ref('')
const localBusy = ref(false)
const previewStates = ref({})

const items = key => props.workspace?.[key] || []
const views = computed(() => items('asset-views').filter(item => !item.stale))
const characters = computed(() => items('characters').filter(item => !item.stale))
const looks = computed(() => items('character-looks').filter(item => !item.stale))
const locations = computed(() => items('locations').filter(item => !item.stale))
const propItems = computed(() => items('props').filter(item => !item.stale))
const assets = computed(() => {
  if (kind.value === 'CHARACTER_LOOK') return looks.value.map(item => ({ ...item, label: `${characters.value.find(c => c.id === item.characterId)?.name || '人物'} · ${item.name}` }))
  if (kind.value === 'LOCATION') return locations.value.map(item => ({ ...item, label: item.name }))
  return propItems.value.map(item => ({ ...item, label: item.name }))
})
const selectedAsset = computed(() => assets.value.find(item => item.id === selectedAssetId.value) || assets.value[0] || null)
const selectedViews = computed(() => {
  const id = selectedAsset.value?.id
  return views.value.filter(item => item.assetId === id && item.assetKind === kind.value).sort((a, b) => viewOrder(a.view) - viewOrder(b.view))
})
const masterView = computed(() => selectedViews.value.find(item => item.master || ['FRONT', 'LAYOUT'].includes(item.view)))
const canGenerateMaster = computed(() => !masterView.value || ['FAILED', 'REJECTED', 'STALE'].includes(masterView.value.status))
const detail = computed(() => selectedViews.value.find(item => item.id === detailId.value) || selectedViews.value[0] || null)
const statusLabel = status => ({ PLANNED: '待生成', GENERATING: '生成中', REVIEW: '待审查', APPROVED: '已批准', FAILED: '失败', REJECTED: '已退回', STALE: '已过期' }[status] || status || '待生成')
const viewLabel = view => ({ FRONT: '正面主视图', LEFT: '左侧视图', RIGHT: '右侧视图', BACK: '背面视图', LAYOUT: '布局主视图', REVERSE: '反向视图', SIDE: '侧面视图', SCALE: '比例参照' }[view] || view)
const viewOrder = view => ({ FRONT: 1, LAYOUT: 1, LEFT: 2, RIGHT: 3, SIDE: 3, BACK: 4, REVERSE: 4, SCALE: 5 }[view] || 99)
const mediaUrl = item => item?.archiveUrl || item?.previewUrl || (item?.simulated ? '' : item?.providerUrl) || ''
const previewState = item => previewStates.value[mediaUrl(item)] || 'loading'
const markPreview = (item, state) => { previewStates.value[mediaUrl(item)] = state }
const canApprove = item => Boolean(mediaUrl(item) && previewState(item) === 'loaded')
const assetApproved = computed(() => selectedViews.value.length > 0 && selectedViews.value.every(item => item.status === 'APPROVED' && item.approved === true))
const assetStatus = computed(() => {
  if (!selectedViews.value.length) return '暂无视图'
  if (selectedViews.value.some(item => item.status === 'FAILED')) return '有失败视图'
  if (assetApproved.value) return '整套已批准'
  if (selectedViews.value.some(item => item.status === 'REVIEW')) return '等待人工审查'
  if (selectedViews.value.some(item => item.status === 'GENERATING')) return '生成中'
  return '待生成'
})

watch([assets, kind], () => {
  if (!selectedAssetId.value || !assets.value.some(item => item.id === selectedAssetId.value)) selectedAssetId.value = assets.value[0]?.id || ''
  detailId.value = ''
}, { immediate: true })

async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options })
  let body = null
  try { body = await response.json() } catch {}
  if (!response.ok) throw new Error(body?.message || '操作失败')
  return body
}
async function act(label, fn) {
  localBusy.value = true
  try { await fn(); emit('message', label, false); emit('refresh') } catch (error) { emit('message', error.message, true) } finally { localBusy.value = false }
}
async function generate() {
  if (!props.project?.id) return
  await act('已提交主视图生成，完成后再生成其他视角', () => api(`/asset-views/projects/${props.project.id}/generate`, { method: 'POST', body: JSON.stringify(selectedAsset.value ? { assetIds: [selectedAsset.value.id] } : {}) }))
}
async function approve(item) {
  if (item.status !== 'REVIEW') return
  if (!canApprove(item)) { emit('message', '图片尚未加载完成，请先打开并检查图片。', true); return }
  if (!window.confirm(`请先查看${viewLabel(item.view)}图片，确认身份、服装/材质、比例、空间结构和光线与当前故事版本一致。确认已完成检查后再批准。`)) return
  const reviewNote = window.prompt('请输入这张视图的人工审查结论', '人工检查通过，已核对身份、比例、材质与连续性')
  if (reviewNote === null || !reviewNote.trim()) return
  await act(`${viewLabel(item.view)}已批准`, () => api(`/asset-views/${item.id}/approve`, { method: 'POST', body: JSON.stringify({ revision: item.revision, reviewNote: reviewNote.trim() }) }))
}
async function regenerate(item) {
  if (item.submissionUncertain) { emit('message', '该视图提交状态不确定，请先核对三方记录，避免重复提交。', true); return }
  await act('已提交单张视图重生', () => api(`/asset-views/${item.id}/regenerate`, { method: 'POST', body: JSON.stringify({ revision: item.revision, reason: '人工审查要求重做' }) }))
}
async function reject(item) {
  if (item.status !== 'REVIEW') return
  const note = window.prompt('请输入退回原因（会保留在这张视图的审查记录中）', '人物身份、服装/材质、比例或空间结构与故事版本不一致')
  if (note === null || !note.trim()) return
  await act('已退回此视图', () => api(`/asset-views/${item.id}/reject`, { method: 'POST', body: JSON.stringify({ revision: item.revision, note: note.trim() }) }))
}
async function archive(item) {
  await act('已重新提交归档', () => api(`/asset-views/${item.id}/archive`, { method: 'POST', body: '{}' }))
}
</script>

<template>
  <main class="workspace asset-review-grid">
    <aside class="asset-sidebar">
      <span class="eyebrow">一致性资产库</span>
      <h2>素材多视图</h2>
      <p>先审查主视图，再逐张批准其他视角。只有整套视图批准后，分镜和视频才会使用它。</p>
      <div class="asset-kind-tabs">
        <button :class="{active:kind==='CHARACTER_LOOK'}" @click="kind='CHARACTER_LOOK'">人物定妆</button>
        <button :class="{active:kind==='LOCATION'}" @click="kind='LOCATION'">场景</button>
        <button :class="{active:kind==='PROP'}" @click="kind='PROP'">道具</button>
      </div>
      <div v-if="!assets.length" class="empty-note">请先在故事核心中确认人物、场景和道具，系统会自动建立资产档案。</div>
      <button v-for="asset in assets" :key="asset.id" class="asset-select" :class="{active:selectedAsset?.id===asset.id}" @click="selectedAssetId=asset.id">
        <span>{{asset.label}}</span><small>{{selectedAsset?.id===asset.id ? assetStatus : '查看视图'}}</small>
      </button>
    </aside>
    <section class="asset-review-main">
      <div v-if="selectedAsset" class="asset-review-head">
        <div><span class="eyebrow">{{kind==='CHARACTER_LOOK'?'人物定妆':kind==='LOCATION'?'场景':'道具'}}</span><h2>{{selectedAsset.label || selectedAsset.name}}</h2><p>{{selectedAsset.description || '核心故事中已确认的资产描述'}}</p></div>
        <div class="asset-actions"><span :class="['pill',assetApproved?'good':'warn']">{{assetStatus}}</span><button class="primary" :disabled="busy||localBusy||!canGenerateMaster" @click="generate">{{masterView?.status==='FAILED'?'重试主视图':selectedViews.length?'主视图已提交':'生成主视图'}}</button></div>
      </div>
      <div v-if="selectedAsset" class="asset-dependency"><b>一致性要求</b><span>版本 v{{selectedViews[0]?.setVersion || 1}}</span><span>视图必须引用同一套定妆、空间布局和道具状态</span><span v-if="assetApproved" class="good-text">已具备分镜/视频使用条件</span><span v-else class="warn-text">尚未完整批准，不能进入分镜与视频</span></div>
      <div v-if="selectedViews.length" class="view-grid">
        <article v-for="item in selectedViews" :key="item.id" class="view-card" :class="{selected:detail?.id===item.id}">
          <button class="view-image" @click="detailId=item.id"><img v-if="mediaUrl(item)" :key="mediaUrl(item)" :src="mediaUrl(item)" :alt="viewLabel(item.view)" @load="markPreview(item,'loaded')" @error="markPreview(item,'failed')"/><span v-else>{{item.status==='GENERATING'?'生成中':item.simulated?'模拟记录没有真实图片':'暂无图片'}}</span></button>
          <p v-if="mediaUrl(item) && previewState(item)==='failed'" class="asset-failure">图片加载失败，请刷新或重试归档，加载完成前无法批准。</p>
          <div class="view-card-head"><h3>{{viewLabel(item.view)}}</h3><span :class="['pill',item.status==='APPROVED'?'good':item.status==='FAILED'?'bad':'warn']">{{statusLabel(item.status)}}</span></div>
          <p v-if="item.status==='FAILED'" class="asset-failure">生成失败，请查看三方记录后单张重试。</p>
          <p v-else class="muted">{{item.approved?'人工已确认连续性':'请人工核对角度、比例、服装/材质和光线'}}</p>
          <small v-if="item.providerRequestId" class="provider-trace">请求ID：{{item.providerRequestId}}</small>
          <small v-if="item.generationJobId" class="provider-trace">任务：{{item.generationJobId}}</small>
          <div class="button-row"><button v-if="item.status==='REVIEW'" class="tiny" :disabled="busy||localBusy||!canApprove(item)" @click="approve(item)">批准此视图</button><button v-if="item.status==='REVIEW'" class="tiny" @click="reject(item)">退回并记录原因</button><button v-if="['FAILED','REJECTED','REVIEW'].includes(item.status)" class="tiny" :disabled="item.submissionUncertain" @click="regenerate(item)">单张重生</button><button v-if="item.archiveStatus==='FAILED' || previewState(item)==='failed'" class="tiny" @click="archive(item)">重试归档</button></div>
          <small v-if="item.submissionUncertain" class="asset-failure">提交状态不确定：{{item.providerRequestId || '暂无请求 ID'}}。请先核对服务商记录。</small>
        </article>
      </div>
      <div v-else-if="selectedAsset" class="asset-empty panel"><h3>还没有视图</h3><p>先生成主视图。主视图通过人工审查后，系统才会安排其他视角。</p><button class="primary" :disabled="busy||localBusy" @click="generate">生成主视图</button></div>
      <div v-if="detail" class="view-detail panel"><div><h3>{{viewLabel(detail.view)}} · v{{detail.setVersion || 1}}</h3><p>{{detail.reviewNote || detail.failureReason || '请检查人物身份、服装材质、空间锚点、比例和光线方向。'}}</p><img v-if="mediaUrl(detail)" :key="mediaUrl(detail)" :src="mediaUrl(detail)" :alt="viewLabel(detail.view)+'完整图片'" style="display:block;width:100%;max-height:80vh;object-fit:contain" @load="markPreview(detail,'loaded')" @error="markPreview(detail,'failed')"/></div><span class="muted">来源快照：{{detail.sourceSnapshot?.continuityHash || detail.sourceSnapshot?.hash || '已绑定核心版本'}}</span></div>
      <div v-if="!selectedAsset" class="asset-empty panel"><h3>等待故事核心</h3><p>确认故事核心后，人物定妆、场景和道具会自动建立，不需要填写图片路径或三方素材 ID。</p></div>
    </section>
  </main>
</template>
