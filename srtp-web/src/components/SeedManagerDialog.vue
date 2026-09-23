<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal" style="width:850px;height:620px">
      <div class="modal-header">
        <h3>种子管理</h3>
        <button class="close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div style="display:flex;gap:8px;margin-bottom:10px">
          <button class="btn small primary" @click="addSeed">新增种子</button>
          <button class="btn small danger" @click="delSeed">删除选中</button>
          <button class="btn small" style="margin-left:auto" @click="confirm">确定</button>
        </div>

        <div class="table-scroll" style="max-height:300px">
          <table class="data-table">
            <thead>
              <tr>
                <th>种子号</th>
                <th>城市数</th>
                <th>边权重数据</th>
                <th>更新时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in seeds" :key="row.seed" :class="{ hl: selected && selected.seed === row.seed }"
                @click="selectRow(row)" @dblclick="loadSeedToEdit(row)" style="cursor:pointer">
                <td>{{ row.seed }}</td>
                <td>{{ row.city_count }}</td>
                <td style="max-width:380px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ row.city_data }}</td>
                <td>{{ row.updated_at }}</td>
              </tr>
              <tr v-if="seeds.length === 0">
                <td colspan="4" style="text-align:center;color:#999;padding:16px">暂无种子数据</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="editVisible" class="edit-panel">
          <div class="input-row">
            <div class="field" style="width:160px">
              <label>种子号</label>
              <input type="number" min="1" max="99999" v-model.number="editSeed" />
            </div>
            <div class="field" style="width:160px">
              <label>城市数</label>
              <input type="number" min="3" max="32768" v-model.number="editCityCount" @blur="validateCityCount" />
            </div>
            <button class="btn small success" @click="genEdges">随机生成边</button>
            <button class="btn small primary" @click="saveSeedEdit">保存</button>
            <button class="btn small" @click="editVisible = false">取消</button>
          </div>
          <textarea class="edge-area" style="height:90px;background:#e8e8e8" v-model="editSeedData"
            placeholder="0->1:100&#10;1->0:80&#10;0->2:150&#10;2->0:145"></textarea>
        </div>
      </div>
    </div>

    <MissingEdgesDialog v-if="showMissing" :edges="missingEdges" @apply="onMissingApply" @cancel="showMissing = false" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { platform, lookupSeed, getInvalidCityLines, getMissingEdges } from '../store/platform'
import { genSeedEdges } from '../utils/edges'
import { notify, confirmDialog } from '../store/message'
import MissingEdgesDialog from './MissingEdgesDialog.vue'

const emit = defineEmits(['close'])
const props = defineProps({ autoSeed: { type: Number, default: null } })

const seeds = ref([])
const selected = ref(null)
const editVisible = ref(false)
const editSeed = ref('')
const editCityCount = ref(8)
const editSeedData = ref('')
const showMissing = ref(false)
const missingEdges = ref([])

onMounted(async () => {
  await loadSeeds()
  if (props.autoSeed) {
    const row = seeds.value.find((s) => s.seed === props.autoSeed)
    if (row) loadSeedToEdit(row)
  }
})
async function loadSeeds() {
  const r = await api.listSeeds()
  if (r.data && Array.isArray(r.data)) seeds.value = r.data
}
function selectRow(row) { selected.value = row }
function addSeed() {
  editSeed.value = ''
  editCityCount.value = 8
  editSeedData.value = ''
  editVisible.value = true
  selected.value = null
}
function loadSeedToEdit(row) {
  if (!row) return
  editSeed.value = row.seed
  editCityCount.value = row.city_count
  editSeedData.value = row.city_data || ''
  editVisible.value = true
  selected.value = row
}
function genEdges() {
  const n = parseInt(editCityCount.value, 10) || 0
  if (n < 3) { notify('请先填写不小于 3 的城市数量', 'warn'); return }
  editSeedData.value = genSeedEdges(n)
}
function validateCityCount() {
  const v = parseInt(editCityCount.value, 10)
  if (isNaN(v) || v < 3) notify('城市数量最少为 3', 'warn')
}

function saveSeedEdit() {
  const seed = parseInt(editSeed.value, 10)
  if (!seed || seed < 1) { notify('请输入有效种子号', 'warn'); return }
  const cityData = (editSeedData.value || '').trim()
  if (!cityData) { notify('请输入边权重数据', 'warn'); return }
  const cc = parseInt(editCityCount.value, 10)
  if (isNaN(cc) || cc < 3) { notify('城市数量最少为 3', 'warn'); return }

  const invalid = getInvalidCityLines(cityData, cc)
  if (invalid.length > 0) {
    notify(`第 ${invalid[0].lineNo} 行包含不存在的城市 ${invalid[0].cityId}（共 ${invalid.length} 处）`, 'error', 4000)
    return
  }

  const missing = getMissingEdges(cityData, cc)
  if (missing.length > 0) {
    missingEdges.value = missing
    showMissing.value = true
    return
  }
  doSave(seed, cc, cityData)
}

function onMissingApply(map) {
  showMissing.value = false
  let current = (editSeedData.value || '').trim()
  const lines = Object.entries(map).map(([k, v]) => `${k}:${v}`)
  if (lines.length > 0) {
    if (current) current += '\n'
    current += lines.join('\n')
    editSeedData.value = current
  }
  // 再次校验是否仍有缺失（未补充的仍会提示）
  const cc = parseInt(editCityCount.value, 10)
  const stillMissing = getMissingEdges(editSeedData.value, cc)
  if (stillMissing.length > 0) {
    missingEdges.value = stillMissing
    showMissing.value = true
    return
  }
  const seed = parseInt(editSeed.value, 10)
  doSave(seed, cc, editSeedData.value)
}

async function doSave(seed, cityCount, cityData) {
  const r = await api.saveSeed({ seed, city_count: cityCount, city_data: cityData, weights: cityData })
  if (r.data && r.data.ok) {
    editVisible.value = false
    await loadSeeds()
    platform.seedInput = seed
    await lookupSeed()
  } else {
    notify((r.data && r.data.error) || '保存失败', 'error')
  }
}

async function delSeed() {
  if (!selected.value) { notify('请选择一条记录，或双击进入编辑', 'warn'); return }
  if (!confirmDialog(`确定删除种子 ${selected.value.seed} 的数据？`)) return
  const r = await api.deleteSeed(selected.value.seed)
  if (r.data && r.data.ok) {
    await loadSeeds()
    if (parseInt(platform.seedInput, 10) === selected.value.seed) {
      platform.edgeText = ''
      platform.cityCount = 0
      platform.seedInfo = { found: false, data: null }
    }
    selected.value = null
  } else {
    notify((r.data && r.data.error) || '删除失败', 'error')
  }
}

async function confirm() {
  if (!selected.value) { notify('请先在表格中选中一条种子记录', 'warn'); return }
  platform.seedInput = selected.value.seed
  await lookupSeed()
  emit('close')
}
</script>

<style scoped>
.hl { background: #e8f4fd !important; }
</style>
