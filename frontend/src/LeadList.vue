<script setup>
defineProps({items: {type: Array, required: true}, empty: {type: String, required: true}});
defineEmits(['open-lead', 'selection-change']);
</script>

<template>
  <tr v-for="{lead, analysis, score, selection} in items" :key="lead.id">
    <td>
      <strong class="lead-row-name">{{ lead.username }}</strong>
      <p class="lead-row-bio">{{ lead.bio }}</p>
    </td>
    <td>{{ lead.region || '—' }} / {{ lead.language || '—' }}</td>
    <td><span class="lead-score">{{ score }}</span></td>
    <td>
      <label v-if="analysis.selectable" class="check">
        <input type="checkbox" :checked="analysis.checked"
               :aria-label="`选择 ${lead.username} 进行语义分析`"
               @change="$emit('selection-change', lead.id, $event.currentTarget)">
        <span>选择</span>
      </label>
      <p>{{ analysis.label }}</p>
    </td>
    <td>
      <span class="pool-status" :data-status="lead.selected ? 'selected' : lead.manual_decision === 'REJECT' ? 'rejected' : 'idle'">
        {{ selection }}
      </span>
    </td>
    <td><button type="button" :aria-label="`查看 ${lead.username} 的详情`" @click="$emit('open-lead', lead)">详情</button></td>
  </tr>
  <tr v-if="!items.length"><td colspan="6" class="lead-empty">{{ empty }}</td></tr>
</template>
