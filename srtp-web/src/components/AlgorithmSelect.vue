<template>
  <div class="section">
    <h3>算法选择</h3>
    <div class="algo-grid">
      <label v-for="a in platform.algoList" :key="a.id">
        <input type="checkbox" :value="a.id" v-model="platform.selectedAlgos" />
        {{ a.label }}
      </label>
    </div>
    <div style="margin-top:8px;display:flex;align-items:center">
      <button class="btn small" @click="selectAll">全选</button>
      <button class="btn small" @click="clearAll">清空</button>
      <button class="btn small" style="margin-left:auto" @click="$emit('open-algo-manager')">算法管理</button>
    </div>
  </div>
</template>

<script setup>
import { platform } from '../store/platform'
defineEmits(['open-algo-manager'])

function selectAll() {
  platform.selectedAlgos = platform.algoList.map((a) => a.id)
}
function clearAll() {
  platform.selectedAlgos = []
}
</script>
