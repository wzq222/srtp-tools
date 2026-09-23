<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal" style="width:920px;height:660px">
      <div class="modal-header">
        <h3>算法管理</h3>
        <button class="close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div style="display:flex;gap:8px;margin-bottom:10px">
          <button class="btn small primary" @click="addAlgo">新增算法</button>
          <button class="btn small danger" @click="delAlgo">删除选中</button>
          <button class="btn small" style="margin-left:auto" @click="confirm">确定</button>
        </div>

        <div class="table-scroll" style="max-height:180px">
          <table class="data-table">
            <thead>
              <tr>
                <th>算法标识</th>
                <th>算法名称</th>
                <th>参数数</th>
                <th>更新时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in algos" :key="row.algo_id" :class="{ hl: selected && selected.algo_id === row.algo_id }"
                @click="selected = row" @dblclick="loadToEdit(row)" style="cursor:pointer">
                <td>{{ row.algo_id }}</td>
                <td>{{ row.label }}</td>
                <td>{{ paramCount(row) }}</td>
                <td>{{ row.updated_at }}</td>
              </tr>
              <tr v-if="algos.length === 0">
                <td colspan="4" style="text-align:center;color:#999;padding:16px">暂无自定义算法</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="editVisible" class="edit-panel">
          <div class="input-row">
            <div class="field" style="width:180px">
              <label>算法标识</label>
              <input v-model="editAlgoId" :disabled="!!editingId" />
            </div>
            <div class="field" style="width:220px">
              <label>算法名称</label>
              <input v-model="editAlgoLabel" />
            </div>
            <button class="btn small success" @click="saveAlgo">保存</button>
            <button class="btn small" @click="editVisible = false">取消</button>
          </div>
          <div style="margin-top:6px">
            <label style="font-size:13px;color:#555">核心代码（Python，须定义 <code>def run(cities, params, seed, weights)</code> 返回 <code>(distance, path)</code>）:</label>
            <textarea v-model="editCode" class="code-area" placeholder="import math&#10;def run(cities, params, seed, weights):&#10;    n = len(cities)&#10;    path = list(range(n))&#10;    # ... 你的算法 ...&#10;    dist = 0.0&#10;    return dist, path"></textarea>
          </div>
          <div style="margin-top:8px">
            <label style="font-size:13px;color:#555">控制变量（变量名 key 对应代码中 params 字典的键；勾选迭代/种群变量用于算力估算）:</label>
            <button class="btn small" style="margin-left:8px" @click="addSpec">添加变量</button>
            <table class="spec-table">
              <thead>
                <tr>
                  <th style="width:150px">变量名(key)</th>
                  <th style="width:150px">显示名(label)</th>
                  <th style="width:70px">类型</th>
                  <th style="width:90px">默认值</th>
                  <th style="width:80px;text-align:center">迭代变量</th>
                  <th style="width:80px;text-align:center">种群变量</th>
                  <th style="width:50px;text-align:center">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(s, i) in specs" :key="i">
                  <td><input v-model="s.key" placeholder="如 iterations" /></td>
                  <td><input v-model="s.label" placeholder="如 迭代次数" /></td>
                  <td>
                    <select v-model="s.type">
                      <option value="int">int</option>
                      <option value="float">float</option>
                    </select>
                  </td>
                  <td><input v-model="s.default" /></td>
                  <td style="text-align:center"><input type="checkbox" v-model="s.is_iter" @change="enforceSingle(s, 'is_iter')" /></td>
                  <td style="text-align:center"><input type="checkbox" v-model="s.is_pop" @change="enforceSingle(s, 'is_pop')" /></td>
                  <td style="text-align:center"><span class="text-link" style="color:#e74c3c" @click="specs.splice(i, 1)">删除</span></td>
                </tr>
              </tbody>
            </table>
            <div class="help-text" style="margin-top:6px">提示：迭代变量和种群变量各只能勾选一个，用于统一算力公式 iterations × population × n²。未勾选时算力按 n² 估算。</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { reloadAlgorithms } from '../store/platform'
import { notify, confirmDialog } from '../store/message'

const emit = defineEmits(['close'])

const algos = ref([])
const selected = ref(null)
const editVisible = ref(false)
const editingId = ref(null)
const editAlgoId = ref('')
const editAlgoLabel = ref('')
const editCode = ref('')
const specs = ref([])

onMounted(loadAlgos)
async function loadAlgos() {
  const r = await api.listCustomAlgos()
  if (r.data && Array.isArray(r.data)) algos.value = r.data
}
function paramCount(row) {
  try { return JSON.parse(row.param_specs || '[]').length } catch (e) { return 0 }
}
function addAlgo() {
  editingId.value = null
  editAlgoId.value = ''
  editAlgoLabel.value = ''
  editCode.value = ''
  specs.value = [blankSpec()]
  editVisible.value = true
  selected.value = null
}
function loadToEdit(row) {
  if (!row) return
  editingId.value = row.algo_id
  editAlgoId.value = row.algo_id
  editAlgoLabel.value = row.label
  editCode.value = row.code || ''
  let parsed = []
  try { parsed = JSON.parse(row.param_specs || '[]') } catch (e) {}
  specs.value = parsed.map((s) => ({
    key: s.key || '',
    label: s.label || '',
    type: s.type || 'int',
    default: s.default != null ? s.default : '',
    is_iter: s.key === row.iter_key,
    is_pop: s.key === row.pop_key
  }))
  editVisible.value = true
}
function blankSpec() { return { key: '', label: '', type: 'int', default: '', is_iter: false, is_pop: false } }
function addSpec() { specs.value.push(blankSpec()) }
function enforceSingle(cur, field) {
  if (cur[field]) specs.value.forEach((s) => { if (s !== cur) s[field] = false })
}

function collectSpecs() {
  const out = []
  let iterKey = ''
  let popKey = ''
  specs.value.forEach((s) => {
    const key = (s.key || '').trim()
    if (!key) return
    out.push({
      key,
      label: (s.label || '').trim() || key,
      type: s.type || 'int',
      default: (s.default != null ? s.default : '').toString().trim(),
      is_iter: !!s.is_iter,
      is_pop: !!s.is_pop
    })
    if (s.is_iter) iterKey = key
    if (s.is_pop) popKey = key
  })
  return { specs: out, iterKey, popKey }
}

async function saveAlgo() {
  const algoId = editAlgoId.value.trim()
  const label = editAlgoLabel.value.trim()
  const code = editCode.value
  if (!algoId) { notify('请输入算法标识', 'warn'); return }
  if (!label) { notify('请输入算法名称', 'warn'); return }
  if (!code.trim()) { notify('请输入算法代码', 'warn'); return }
  if (!/^[A-Za-z0-9_]+$/.test(algoId)) { notify('算法标识只能用字母、数字、下划线', 'warn'); return }
  const { specs: sp, iterKey, popKey } = collectSpecs()
  const r = await api.saveCustomAlgo({
    algo_id: algoId,
    label,
    code,
    param_specs: JSON.stringify(sp),
    iter_key: iterKey,
    pop_key: popKey
  })
  if (r.data && r.data.ok) {
    notify('算法已保存', 'success')
    editVisible.value = false
    await loadAlgos()
  } else {
    notify((r.data && r.data.error) || '保存失败', 'error')
  }
}

async function delAlgo() {
  if (!selected.value) { notify('请先选中一条算法', 'warn'); return }
  if (!confirmDialog(`确定删除算法 "${selected.value.label}" ？`)) return
  const r = await api.deleteCustomAlgo(selected.value.algo_id)
  if (r.data && r.data.ok) {
    await loadAlgos()
    await reloadAlgorithms()
  } else {
    notify((r.data && r.data.error) || '删除失败', 'error')
  }
}

async function confirm() {
  emit('close')
  await reloadAlgorithms()
}
</script>

<style scoped>
.hl { background: #e8f4fd !important; }
</style>
