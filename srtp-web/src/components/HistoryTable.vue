<template>
  <div class="section">
    <h3>历史记录</h3>
    <div class="input-row" style="margin-bottom:10px">
      <div class="field" style="width:160px">
        <label>算法</label>
        <select v-model="platform.historyFilters.algorithm" class="native-select">
          <option v-for="o in platform.algoFilterOptions" :key="o.id" :value="o.id">{{ o.label }}</option>
        </select>
      </div>
      <div class="field" style="width:150px">
        <label>日期</label>
        <input type="date" v-model="platform.historyFilters.date" />
      </div>
      <div class="field" style="width:100px">
        <label>城市数</label>
        <input type="number" min="1" v-model="platform.historyFilters.cityCount" />
      </div>
      <div class="field" style="width:130px">
        <label>种子号</label>
        <input type="text" v-model="platform.historyFilters.seed" placeholder="如 3 或 12-3" />
      </div>
      <div class="field" style="width:110px">
        <label>ID</label>
        <input type="number" min="1" v-model="platform.historyFilters.recordId" />
      </div>
      <button class="btn small" @click="queryHistory">查询</button>
      <button class="btn small" @click="clearFilter">清空</button>
      <button class="btn small danger" style="margin-left:auto" @click="onDelete">批量删除</button>
      <button class="btn small" @click="onRefresh">刷新</button>
    </div>
    <div class="help-text" style="margin-bottom:8px">
      种子号格式：<b>算法种子号-城市种子号</b>。算法种子号 = 算法参数种子管理中保存的参数组编号（0 表示未匹配到已保存种子）；城市种子号 = 种子管理中保存的城市边权重数据编号。例：12-3 表示第 12 号算法参数组 + 第 3 号城市数据。
    </div>

    <div class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th class="col-check"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></th>
            <th>ID</th>
            <th>算法</th>
            <th>城市数</th>
            <th>种子号(算法-城市)</th>
            <th>最优距离</th>
            <th>耗时</th>
            <th>算力</th>
            <th>日期</th>
            <th>路线图</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in pagedRows" :key="row.id">
            <td class="col-check"><input type="checkbox" :value="row.id" v-model="checked" /></td>
            <td>{{ row.id }}</td>
            <td>{{ row.algorithm_label }}</td>
            <td>{{ row.city_count }}</td>
            <td>{{ fmtSeed(row) }}</td>
            <td>{{ fmtDist(row.best_distance) }}</td>
            <td>{{ formatTime(row.elapsed_ms) }}</td>
            <td>{{ row.complexity_label }}</td>
            <td>{{ row.created_at }}</td>
            <td>
              <span v-if="hasPath(row)" class="link" @click="viewPath(row)">查看</span>
              <span v-else class="muted">无数据</span>
            </td>
          </tr>
          <tr v-if="pagedRows.length === 0">
            <td colspan="10" style="text-align:center;color:#999;padding:18px">暂无记录</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button class="btn small" :disabled="platform.historyPage <= 1" @click="platform.historyPage--">上一页</button>
      <span>第 {{ platform.historyPage }} / {{ totalPages }} 页，共 {{ platform.history.length }} 条</span>
      <button class="btn small" :disabled="platform.historyPage >= totalPages" @click="platform.historyPage++">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { platform, queryHistory, deleteHistory, refreshHistorySeeds } from '../store/platform'
import { formatTime, fmtDist } from '../utils/format'
import { notify } from '../store/message'

const emit = defineEmits(['show-path'])
const checked = ref([])

const totalPages = computed(() => Math.max(1, Math.ceil(platform.history.length / platform.historyPageSize)))
const pagedRows = computed(() => {
  const start = (platform.historyPage - 1) * platform.historyPageSize
  return platform.history.slice(start, start + platform.historyPageSize)
})
const allChecked = computed(() => pagedRows.value.length > 0 && pagedRows.value.every((r) => checked.value.includes(r.id)))

function toggleAll(e) {
  if (e.target.checked) {
    checked.value = pagedRows.value.map((r) => r.id)
  } else {
    checked.value = []
  }
}

function fmtSeed(row) {
  const as = row.algo_seed != null ? row.algo_seed : 0
  const cs = row.city_seed != null ? row.city_seed : (row.seed || 0)
  return as + '-' + cs
}
function hasPath(row) {
  return row.best_path && row.best_path !== 'null'
}
function viewPath(row) {
  let bestPath
  try {
    bestPath = JSON.parse(row.best_path)
  } catch (e) {
    bestPath = String(row.best_path).split(',').map(Number)
  }
  if (!Array.isArray(bestPath) || bestPath.length < 2) {
    notify('路径数据格式错误', 'error')
    return
  }
  emit('show-path', { algorithm_label: row.algorithm_label || row.algorithm_id, best_path: bestPath, input_text: row.input_text || '' })
}

function clearFilter() {
  platform.historyFilters = { algorithm: 'all', date: '', cityCount: '', seed: '', recordId: '' }
  queryHistory()
}
function onDelete() {
  const rows = platform.history.filter((r) => checked.value.includes(r.id))
  deleteHistory(rows).then(() => { checked.value = [] })
}
function onRefresh() { refreshHistorySeeds() }
</script>

<style scoped>
.native-select { width: 100%; padding: 7px 8px; border: 1px solid #ccc; border-radius: 4px; font-size: 13px; background: #fff; }
</style>
