<template>
  <div class="main-layout">
    <div class="header-bar">
      <h2>SRTP TSP 多算法优化平台</h2>
      <span class="user-info">用户: {{ session.username }}</span>
      <button class="btn small" style="background:#e74c3c;border-color:#c0392b;color:#fff" @click="onLogout">退出</button>
    </div>

    <div class="content-wrap">
      <AlgorithmSelect @open-algo-manager="showAlgoManager = true" />

      <CityDataSection
        @open-seed-manager="openSeedManager"
        @edit-seed="onEditSeed" />

      <ParamPanel
        @open-import-seed="openAlgoSeedManager"
        @save-seed="saveCurrentParamsAsSeed" />

      <div class="run-btn-wrap">
        <button class="btn primary" style="width:200px;height:40px;font-size:16px"
          :disabled="platform.running" @click="runAlgorithms">
          {{ platform.running ? '运行中…' : '运行选中算法' }}
        </button>
      </div>

      <div v-if="platform.currentResults.length" class="section">
        <h3>结果对比
          <span style="font-size:12px;color:#999;font-weight:normal;margin-left:10px">
            城市数: {{ pathCityCount }} | 共 {{ platform.currentResults.length }} 个算法
          </span>
        </h3>
        <ResultChart :results="platform.currentResults" @show-path="onShowPath" />
      </div>

      <HistoryTable @show-path="onShowPath" />
    </div>

    <!-- 对话框 -->
    <SeedManagerDialog v-if="showSeedManager" :auto-seed="seedToEdit" @close="showSeedManager = false" />
    <CustomAlgoDialog v-if="showAlgoManager" @close="showAlgoManager = false" />
    <AlgoSeedDialog v-if="showAlgoSeedManager" @close="showAlgoSeedManager = false" />
    <PathDialog v-if="showPathDialog && pathResult" :title="pathTitle" :result="pathResult" @close="showPathDialog = false" />

    <!-- 运行遮罩 -->
    <div v-if="platform.running" class="run-mask">
      <span class="spinner"></span> 算法正在执行，请稍候…
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { session, logout } from '../store/session'
import {
  platform, loadAlgorithms, queryHistory, runAlgorithms,
  saveCurrentParamsAsSeed, lookupSeed
} from '../store/platform'
import { notify } from '../store/message'

import AlgorithmSelect from '../components/AlgorithmSelect.vue'
import CityDataSection from '../components/CityDataSection.vue'
import ParamPanel from '../components/ParamPanel.vue'
import ResultChart from '../components/ResultChart.vue'
import HistoryTable from '../components/HistoryTable.vue'
import SeedManagerDialog from '../components/SeedManagerDialog.vue'
import CustomAlgoDialog from '../components/CustomAlgoDialog.vue'
import AlgoSeedDialog from '../components/AlgoSeedDialog.vue'
import PathDialog from '../components/PathDialog.vue'

const showSeedManager = ref(false)
const showAlgoManager = ref(false)
const showAlgoSeedManager = ref(false)
const showPathDialog = ref(false)
const pathResult = ref(null)
const seedToEdit = ref(null)

const pathCityCount = computed(() => {
  const r = platform.currentResults[0]
  return r && r.best_path ? r.best_path.length : '?'
})
const pathTitle = computed(() => (pathResult.value ? pathResult.value.algorithm_label + ' 路径图' : '路径图'))

onMounted(async () => {
  await loadAlgorithms()
  await queryHistory()
})

function openSeedManager() { seedToEdit.value = null; showSeedManager.value = true }
function onEditSeed() {
  seedToEdit.value = parseInt(platform.seedInput, 10) || null
  showSeedManager.value = true
}
function openAlgoSeedManager() { showAlgoSeedManager.value = true }

function onShowPath(result) {
  pathResult.value = result
  showPathDialog.value = true
}

async function onLogout() {
  await logout()
  notify('已退出登录', 'info')
}
</script>
