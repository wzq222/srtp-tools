<template>
  <div class="section">
    <h3>城市数据（有向边权重）</h3>
    <div class="input-row">
      <div class="field" style="width:180px">
        <label>城市数量</label>
        <input :value="platform.cityCount || ''" readonly @click="guideClick" />
      </div>
      <div class="field" style="width:180px">
        <label>种子</label>
        <input :value="platform.seedInput || ''" readonly @click="guideClick" />
      </div>
      <button class="btn" @click="$emit('open-seed-manager')">种子管理</button>
    </div>

    <div v-if="platform.seedInfo.found" class="seed-info-bar info">
      当前种子：<b>{{ platform.seedInfo.data.seed }}</b>，城市数：<b>{{ platform.seedInfo.data.city_count }}</b>
      <a @click="$emit('edit-seed')">点击编辑</a>
    </div>
    <div v-else class="seed-info-bar warn">
      请先在<a @click="$emit('open-seed-manager')">种子管理</a>处添加城市和边权重数据。
    </div>

    <div class="help-text">格式（有向）：起点-&gt;终点:权重。反向需单独设置。例：0-&gt;1:100 1-&gt;0:80  每行一条边</div>
    <textarea class="edge-area" readonly :value="platform.edgeText"
      placeholder="0->1:100&#10;1->0:80&#10;0->2:150&#10;2->0:145&#10;1->2:200&#10;2->1:195"
      @click="showHint"></textarea>
    <div v-if="hintVisible" class="help-text" style="color:#3498db">此区域不允许直接编辑，请使用种子管理进行修改</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { platform } from '../store/platform'
import { notify } from '../store/message'

defineEmits(['open-seed-manager', 'edit-seed'])

const hintVisible = ref(false)
let hintTimer = null
function showHint() {
  hintVisible.value = true
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => { hintVisible.value = false }, 3000)
}
function guideClick() {
  notify('此项不可手动输入，请点击「种子管理」按钮导入或选择种子，城市数量和种子会自动填入。', 'info', 3000)
}
</script>
