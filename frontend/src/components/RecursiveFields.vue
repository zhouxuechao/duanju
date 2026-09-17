<script setup>
import { computed } from 'vue'

defineOptions({ name: 'RecursiveFields' })
const props = defineProps({
  modelValue: { type: [Object, Array], default: () => ({}) },
  depth: { type: Number, default: 0 },
  labelMap: { type: Object, default: () => ({}) },
  arrayName: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const isArray = computed(() => Array.isArray(props.modelValue))
const entries = computed(() => isArray.value
  ? props.modelValue.map((value, index) => [index, value])
  : Object.entries(props.modelValue || {}))
const builtinLabels = { title: '标题', name: '名称', description: '描述', summary: '摘要', state: '状态', age: '年龄', face: '脸型', hair: '发型', body: '体态', voiceDialect: '声音与方言', layout: '空间布局', spatialAnchors: '空间锚点', lighting: '光线', appearance: '外观', scale: '尺寸比例', ownership: '归属', characterKey: '人物标识', locationKey: '场景标识', propKey: '道具标识', lookKey: '定妆标识', looks: '定妆', scenes: '场景列表', scenePlan: '场景计划', duration: '时长', startState: '开场状态', endState: '结尾状态', characterKeys: '人物标识列表', locationKeys: '场景标识列表', propKeys: '道具标识列表' }
const label = key => props.labelMap[key] || builtinLabels[key] || String(key).replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase())
const update = (key, value) => {
  const next = isArray.value ? [...props.modelValue] : { ...(props.modelValue || {}) }
  next[key] = value
  emit('update:modelValue', next)
}
const isObject = value => value && typeof value === 'object'
const numeric = value => typeof value === 'number'
const readOnlyKey = key => /Key(s)?$/.test(String(key))
const editableArray = ['looks', 'scenes', 'scenePlan'].includes(props.arrayName)
const makeItem = () => props.arrayName === 'looks' ? { lookKey: `look-${Date.now()}`, name: '', description: '' } : { name: '', description: '', duration: 0 }
const addArrayItem = () => emit('update:modelValue', [...props.modelValue, makeItem()])
const removeArrayItem = index => emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
</script>

<template>
  <div class="rf" :class="{ 'rf-root': depth === 0 }">
    <div v-for="[key, value] in entries" :key="String(key)" class="rf-row">
      <div class="rf-label">{{ isArray ? `第 ${Number(key) + 1} 项` : label(key) }}</div>
      <RecursiveFields v-if="isObject(value)" :model-value="value" :depth="depth + 1" :label-map="labelMap" :array-name="String(key)" @update:model-value="update(key, $event)" />
      <textarea v-else-if="String(value || '').length > 60" :value="value" rows="2" @input="update(key, numeric(value) ? Number($event.target.value) : $event.target.value)" />
      <input v-else :type="numeric(value) ? 'number' : 'text'" :value="value" :readonly="readOnlyKey(key)" @input="update(key, numeric(value) ? Number($event.target.value) : $event.target.value)" />
    </div>
    <div v-if="isArray && editableArray" class="rf-array-actions"><button class="tiny rf-add" type="button" @click="addArrayItem">添加{{ arrayName === 'looks' ? '定妆' : '场景' }}</button><button v-for="(_, index) in modelValue" :key="index" class="tiny rf-remove" type="button" @click="removeArrayItem(index)">删除第{{ Number(index)+1 }}项</button></div>
  </div>
</template>
