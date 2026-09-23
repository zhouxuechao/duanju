<script setup>
import { computed, ref } from 'vue'
const props=defineProps({versions:Array,episode:Object})
const emit=defineEmits(['message'])
const scope=ref('FROM_CURRENT_POINT'),plan=ref(null),rebuild=ref(null),loading=ref(false)
const pair=computed(()=>[...(props.versions||[])].sort((a,b)=>b.version-a.version).slice(0,2))
async function api(path,options={}){const response=await fetch('/api'+path,{headers:{'Content-Type':'application/json'},...options});const body=await response.json();if(!response.ok)throw new Error(body.message||'操作失败');return body}
async function analyze(){if(pair.value.length<2)return;loading.value=true;try{const [target,source]=pair.value;plan.value=await api(`/script-versions/${source.id}/impact/${target.id}`,{method:'POST',body:JSON.stringify({changeScope:scope.value,changePoint:{episodeId:props.episode?.id,episodeNo:props.episode?.episodeNo,scriptVersionId:target.id}})});rebuild.value=null}catch(error){emit('message',error.message,true)}finally{loading.value=false}}
async function prepare(){try{rebuild.value=await api(`/impact-plans/${plan.value.id}/rebuild-plan`,{method:'POST'});emit('message','重建计划已保存，尚未执行任何生成')}catch(error){emit('message',error.message,true)}}
</script>
<template><section><h3>影响分析</h3><label>生效范围<select v-model="scope"><option>FROM_CURRENT_POINT</option><option>FROM_EPISODE</option><option>FROM_SCENE</option><option>FROM_STORY_TIME</option><option>FROM_FIRST_APPEARANCE</option><option>GLOBAL</option></select></label><div class="button-row"><button :disabled="pair.length<2||loading" @click="analyze">分析影响</button><button :disabled="!plan" @click="prepare">生成重建计划</button></div><template v-if="plan"><p><b>{{plan.impactType}}</b> · 仅向后检查</p><small>受影响：{{plan.affectedEpisodes?.length||0}} 集 / {{plan.affectedScenes?.length||0}} 场 / {{plan.affectedShots?.length||0}} 镜</small><small>图片 {{plan.affectedKeyframes?.length||0}} · 视频 {{plan.affectedVideos?.length||0}} · 音频 {{plan.affectedAudio?.length||0}}</small><p class="muted">历史版本和历史媒体保留，不会自动重建。</p></template><p v-if="rebuild" class="pill good">重建计划 {{rebuild.status}} · 等待用户确认执行</p></section></template>
