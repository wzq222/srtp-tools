<template>
  <div v-if="rows.length" class="result-chart">
    <div v-for="(r, i) in sorted" :key="i" class="result-bar">
      <div class="bar-rank">#{{ i + 1 }}</div>
      <div class="bar-label">{{ r.algorithm_label }}</div>
      <div class="bar-fill-wrap">
        <div class="bar-fill" :style="{ width: Math.max(pct(r), 2) + '%', background: colors[i % colors.length] }"></div>
      </div>
      <div class="bar-meta">
        <span>{{ fmtDist(r.best_distance) }}</span>
        <span>{{ formatTime(r.elapsed_ms) }}</span>
        <span style="font-size:10px;color:#888">算力: {{ r.complexity_label || 'N/A' }}</span>
      </div>
      <button class="route-btn" @click="$emit('show-path', r)">路径</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatTime, fmtDist } from '../utils/format'

const props = defineProps({ results: { type: Array, default: () => [] } })
defineEmits(['show-path'])

const colors = ['#2ecc71', '#3498db', '#9b59b6', '#e67e22', '#1abc9c', '#e74c3c', '#f39c12', '#34495e']

const sorted = computed(() => {
  return [...props.results].sort((a, b) => a.best_distance - b.best_distance)
})
const distances = computed(() => sorted.value.map((r) => r.best_distance).filter((v) => typeof v === 'number' && !isNaN(v)))
const maxDist = computed(() => (distances.value.length ? Math.max.apply(null, distances.value) : 1))

function pct(r) {
  const d = r.best_distance
  if (typeof d !== 'number' || isNaN(d)) return 2
  return ((maxDist.value - d) / maxDist.value) * 100
}
</script>
