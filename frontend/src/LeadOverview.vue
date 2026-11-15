<script setup>
defineProps({metrics: Array});

const labels = ['已导入线索', '当前目标池', '待规则评分'];
</script>

<template>
  <dl class="overview-metrics" aria-live="polite">
    <div v-for="(label, index) in labels" :key="label" class="overview-item">
      <dt class="overview-label">{{ label }}</dt>
      <dd class="overview-value">{{ metrics?.[index] ?? '—' }}</dd>
    </div>
  </dl>
</template>
