<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal" style="width:750px;height:560px">
      <div class="modal-header">
        <h3>算法参数种子管理 — {{ activeLabel }}</h3>
        <button class="close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div class="input-row">
          <div class="field" style="width:160px">
            <label>搜索种子号</label>
            <input v-model="searchVal" placeholder="输入前部分的种子号" />
          </div>
          <button class="btn small" @click="search">搜索</button>
          <button class="btn small primary" @click="showAdd">添加种子</button>
          <button class="btn small" :disabled="!selected" @click="modify">修改种子</button>
          <button class="btn small danger" :disabled="!selected" @click="delSeed">删除种子</button>
        </div>

        <div class="table-scroll" style="max-height:280px">
          <table class="data-table">
            <thead>
              <tr>
                <th>种子号</th>
                <th>参数</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in platform.paramSeeds" :key="row.id" :class="{ hl: selected && selected.id === row.id }"
                @click="onSelect(row)" style="cursor:pointer">
                <td>{{ row.seed_number }}</td>
                <td style="max-width:440px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ row.params_display }}</td>
                <td>{{ row.created_at }}</td>
              </tr>
              <tr v-if="platform.paramSeeds.length === 0">
                <td colspan="3" style="text-align:center;color:#999;padding:16px">暂无参数种子</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="editVisible" class="edit-panel">
          <div class="params-grid">
            <div class="param-item" v-for="p in activeParams" :key="p.key" style="margin-bottom:4px">
              <label>{{ p.label }}</label>
              <input type="text" v-model="formParams[p.key]" :data-key="p.key" :data-type="p.type" />
            </div>
          </div>
          <div style="margin-top:8px">
            <button class="btn small success" @click="saveEdit">完成</button>
            <button class="btn small" style="margin-left:8px" @click="editVisible = false">取消</button>
          </div>
        </div>

        <div style="text-align:right;margin-top:12px">
          <button class="btn" @click="$emit('close')">取消</button>
          <button class="btn primary" style="margin-left:8px" @click="confirmImport">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { platform, loadParamSeeds, addAlgoSeed, updateAlgoSeed, deleteAlgoSeed, importAlgoSeed } from '../store/platform'
import { notify } from '../store/message'

const emit = defineEmits(['close'])

const selected = ref(null)
const editVisible = ref(false)
const editingId = ref(null)
const searchVal = ref('')
const formParams = reactive({})

const activeDef = computed(() => platform.algoParamDefs[platform.activeAlgoId])
const activeLabel = computed(() => (activeDef.value ? activeDef.value.label : platform.activeAlgoId))
const activeParams = computed(() => (activeDef.value && activeDef.value.params) || [])

onMounted(() => loadParamSeeds())

function search() { loadParamSeeds(searchVal.value.trim() || null) }
function onSelect(row) { selected.value = row }

function showAdd() {
  selected.value = null
  editingId.value = null
  renderForm({})
  editVisible.value = true
}
function modify() {
  if (!selected.value) { notify('请先选中一条种子', 'warn'); return }
  editingId.value = selected.value.id
  let params = {}
  try { params = JSON.parse(selected.value.params || '{}') } catch (e) { params = {} }
  renderForm(params)
  editVisible.value = true
}
function renderForm(params) {
  activeParams.value.forEach((p) => {
    const v = params[p.key] != null ? params[p.key] : (p.default != null ? p.default : '')
    formParams[p.key] = v
  })
}
function collectForm() {
  const params = {}
  activeParams.value.forEach((p) => {
    const val = String(formParams[p.key] != null ? formParams[p.key] : '').trim()
    if (val !== '') {
      if (p.type === 'int') params[p.key] = parseInt(val, 10)
      else if (p.type === 'float') params[p.key] = parseFloat(val)
      else params[p.key] = val
    }
  })
  return params
}
async function saveEdit() {
  const params = collectForm()
  if (editingId.value) {
    const ok = await updateAlgoSeed(editingId.value, params)
    if (ok) editVisible.value = false
  } else {
    const ok = await addAlgoSeed(params)
    if (ok) editVisible.value = false
  }
}
async function delSeed() {
  await deleteAlgoSeed(selected.value)
  selected.value = null
}
function confirmImport() {
  if (!selected.value) { notify('请先选中一条种子', 'warn'); return }
  importAlgoSeed(selected.value)
  emit('close')
}
</script>

<style scoped>
.hl { background: #e8f4fd !important; }
</style>
