import { reactive } from 'vue'
import { api } from '../api'
import { parseEdges, getInvalidCityLines, getMissingEdges } from '../utils/edges'
import { notify, confirmDialog } from './message'

export const platform = reactive({
  // 算法
  algoList: [],
  algoParamDefs: {},          // id -> { id, label, is_custom, params:[{key,label,type,default}] }
  activeAlgoId: '',
  algoSeeds: {},              // id -> seedNumber(0 表示未匹配)
  selectedAlgos: [],          // 勾选的算法 id
  algoFilterOptions: [],      // 历史筛选下拉用 [{id,label}]
  // 城市 / 种子
  seedInput: '',              // 城市种子号
  cityCount: 0,
  edgeText: '',
  seedInfo: { found: false, data: null },
  // 运行
  currentResults: [],
  running: false,
  // 历史
  history: [],
  historyFilters: { algorithm: 'all', date: '', cityCount: '', seed: '', recordId: '' },
  historyPage: 1,
  historyPageSize: 10,
  // 算法参数种子（弹窗用，按 activeAlgoId 加载）
  paramSeeds: []
})

// ============ 算法 ============
export async function loadAlgorithms() {
  const r = await api.listAlgorithms()
  if (r.data && Array.isArray(r.data)) {
    platform.algoList = r.data
    platform.selectedAlgos = r.data.map((a) => a.id)
    platform.algoFilterOptions = [{ id: 'all', label: '全部' }].concat(
      r.data.map((a) => ({ id: a.id, label: a.label }))
    )
    await loadParamDefs()
  }
}

export async function loadParamDefs() {
  const r = await api.getParams(platform.cityCount || 0)
  if (r.data && Array.isArray(r.data.algorithms)) {
    const defs = {}
    r.data.algorithms.forEach((a) => { defs[a.id] = a })
    platform.algoParamDefs = defs
    const firstId = Object.keys(defs)[0] || ''
    if (!platform.activeAlgoId || !defs[platform.activeAlgoId]) {
      platform.activeAlgoId = firstId
    }
  }
}

export async function reloadAlgorithms() {
  await loadAlgorithms()
}

export function setActiveAlgo(id) {
  platform.activeAlgoId = id
}

export function resetDefaultParams() {
  const def = platform.algoParamDefs[platform.activeAlgoId]
  if (!def || !def.params) return
  def.params.forEach((p) => {
    const el = document.querySelector(
      `.algo-form[data-algo-id="${platform.activeAlgoId}"] input[data-key="${p.key}"]`
    )
    if (el) el.value = p.default != null ? p.default : ''
  })
  platform.algoSeeds[platform.activeAlgoId] = 0
}

// ============ 种子（城市边权重） ============
export function showNoSeedHint() {
  platform.seedInfo = { found: false, data: null }
}

export function showSeedInfo(data) {
  platform.seedInfo = { found: true, data }
}

export async function lookupSeed() {
  const seed = parseInt(platform.seedInput, 10)
  if (!seed || seed < 1) {
    platform.edgeText = ''
    platform.cityCount = 0
    showNoSeedHint()
    return
  }
  const r = await api.seedInfo(seed)
  if (r.data && r.data.found && r.data.data) {
    platform.edgeText = r.data.data.city_data || ''
    platform.cityCount = r.data.data.city_count
    showSeedInfo(r.data.data)
  } else {
    platform.edgeText = ''
    showNoSeedHint()
  }
}

// ============ 参数收集 ============
export function collectAllParams() {
  const result = {}
  Object.keys(platform.algoParamDefs).forEach((id) => {
    const def = platform.algoParamDefs[id]
    const params = {}
    document.querySelectorAll(`.algo-form[data-algo-id="${id}"] input[data-key]`).forEach((el) => {
      const key = el.dataset.key
      const val = el.value.trim()
      if (val !== '') {
        const type = el.dataset.type
        if (type === 'int') params[key] = parseInt(val, 10)
        else if (type === 'float') params[key] = parseFloat(val)
        else params[key] = val
      }
    })
    result[id] = params
  })
  return result
}

export function collectFormParams(algoId) {
  const params = {}
  document.querySelectorAll(`.algo-form[data-algo-id="${algoId}"] input[data-key]`).forEach((el) => {
    const key = el.dataset.key
    const val = el.value.trim()
    const type = el.dataset.type
    if (val !== '') {
      if (type === 'int') params[key] = parseInt(val, 10)
      else if (type === 'float') params[key] = parseFloat(val)
      else params[key] = val
    }
  })
  return params
}

// ============ 运行 ============
export async function runAlgorithms() {
  if (platform.running) return
  const algoIds = platform.selectedAlgos
  if (!algoIds || algoIds.length === 0) {
    notify('请选择至少一个算法', 'warn')
    return
  }
  const edgeText = platform.edgeText.trim()
  if (!edgeText) {
    notify('请先在种子管理中设置城市数据', 'warn')
    return
  }
  const seed = parseInt(platform.seedInput, 10) || 42
  const parsed = parseEdges(edgeText)
  if (parsed.cities.length < 2) {
    notify('至少需要 2 个城市', 'warn')
    return
  }
  // 重复运行检测
  const r = await api.checkDuplicate({
    algorithms: algoIds,
    algo_seeds: platform.algoSeeds,
    city_seed: seed
  })
  if (r.data && r.data.ok && Array.isArray(r.data.duplicates) && r.data.duplicates.length > 0) {
    const lines = r.data.duplicates.map((d) =>
      `${d.algorithm_label}：种子号 ${d.algo_seed}-${d.city_seed} 已运行 ${d.count} 次（记录 ID: ${d.ids.join(', ')}）`
    )
    const ok = confirmDialog(
      '以下种子组合已经运行过：\n\n' + lines.join('\n') +
      '\n\n是否再次运行？（将生成新的历史记录，新记录 ID 不同，原记录保留）'
    )
    if (!ok) return
  }
  await doRun()
}

async function doRun() {
  const algoIds = platform.selectedAlgos
  const edgeText = platform.edgeText.trim()
  const seed = parseInt(platform.seedInput, 10) || 42
  const parsed = parseEdges(edgeText)

  platform.running = true
  try {
    const r = await api.run({
      algorithms: algoIds,
      cities: parsed.cities,
      params: collectAllParams(),
      seed: seed,
      city_seed: seed,
      algo_seeds: platform.algoSeeds,
      weights: parsed.weights,
      input_text: edgeText
    })
    if (!r.data || !r.data.ok) {
      notify((r.data && r.data.error) || '未知错误', 'error')
      return
    }
    platform.currentResults = (r.data.results || []).filter((x) => !x.error)
    const errResults = (r.data.results || []).filter((x) => x.error)
    if (errResults.length > 0) {
      notify('部分算法失败：\n' + errResults.map((x) => x.algorithm_label + ': ' + x.error).join('\n'), 'error', 4000)
    }
    await queryHistory()
  } catch (e) {
    notify('运行失败：' + (e.message || e), 'error')
  } finally {
    platform.running = false
  }
}

// ============ 历史 ============
export async function queryHistory() {
  const f = platform.historyFilters
  let algoSeed = ''
  let citySeed = ''
  const seedStr = (f.seed || '').trim()
  if (seedStr) {
    if (seedStr.indexOf('-') >= 0) {
      const parts = seedStr.split('-')
      const a = parseInt(parts[0], 10)
      const c = parseInt(parts[1], 10)
      algoSeed = isNaN(a) ? '' : a
      citySeed = isNaN(c) ? '' : c
    } else {
      const c2 = parseInt(seedStr, 10)
      citySeed = isNaN(c2) ? '' : c2
    }
  }
  const r = await api.queryHistory({
    algorithm: f.algorithm || 'all',
    date: f.date || '',
    cityCount: f.cityCount || '',
    algoSeed,
    citySeed,
    recordId: f.recordId || ''
  })
  if (r.data && Array.isArray(r.data)) {
    platform.history = r.data
    platform.historyPage = 1
  }
}

export async function deleteHistory(rows) {
  if (!rows || rows.length === 0) {
    notify('请选择要删除的记录', 'warn')
    return
  }
  if (!confirmDialog(`确定要删除选中的 ${rows.length} 条记录？`)) return
  const ids = rows.map((r) => r.id)
  const r = await api.deleteHistory(ids)
  if (r.data && r.data.ok) {
    notify('已删除', 'success')
    await queryHistory()
  } else {
    notify((r.data && r.data.error) || '删除失败', 'error')
  }
}

export async function refreshHistorySeeds() {
  const r = await api.refreshSeeds()
  if (!r.data || !r.data.ok) {
    notify((r.data && r.data.error) || '刷新失败', 'error')
    return
  }
  const msg = `已重置 ${r.data.updated} 条已删除种子的记录`
  const changed = r.data.changed_seeds
  if (changed && changed.length > 0) {
    const parts = changed.map((s) => `${s.algorithm_label} 的 ${s.algo_seed} 号种子`)
    const ok = confirmDialog(
      msg + '\n\n以下种子参数已变更：\n' + parts.join('\n') + '\n\n是否重新推演？'
    )
    if (ok) {
      const ids = changed.map((s) => s.history_id)
      const rr = await api.rerun(ids)
      if (rr.data && rr.data.ok) notify(`已重推演 ${rr.data.rerun_count} 条记录`, 'success')
      else notify((rr.data && rr.data.error) || '重推演失败', 'error')
    }
  } else if (r.data.updated > 0) {
    notify(msg, 'success')
  } else {
    notify('没有需要刷新的记录', 'info')
  }
  await queryHistory()
}

// ============ 算法参数种子 ============
export async function loadParamSeeds(searchSeed) {
  const r = await api.listAlgoSeeds(platform.activeAlgoId)
  if (r.data && Array.isArray(r.data)) {
    let list = r.data
    if (searchSeed) {
      list = list.filter((s) => String(s.seed_number).indexOf(String(searchSeed)) === 0)
    }
    platform.paramSeeds = list
  }
}

export async function addAlgoSeed(params) {
  const r = await api.addAlgoSeed({ algorithm_id: platform.activeAlgoId, params })
  if (r.data && r.data.ok) {
    platform.algoSeeds[platform.activeAlgoId] = r.data.seed_number
    notify('参数已保存为种子，种子号: ' + r.data.seed_number, 'success')
    await loadParamSeeds()
    return true
  }
  notify((r.data && r.data.error) || '保存失败', 'error')
  return false
}

export async function updateAlgoSeed(id, params) {
  const r = await api.updateAlgoSeed({ id, algorithm_id: platform.activeAlgoId, params })
  if (r.data && r.data.ok) {
    notify('修改成功', 'success')
    await loadParamSeeds()
    return true
  }
  notify((r.data && r.data.error) || '修改失败', 'error')
  return false
}

export async function deleteAlgoSeed(row) {
  if (!row) { notify('请先选中一条种子', 'warn'); return }
  if (!confirmDialog(`确定删除种子 ${row.seed_number} ？`)) return
  const r = await api.deleteAlgoSeed(row.id)
  if (r.data && r.data.ok) {
    notify('种子已删除', 'success')
    await loadParamSeeds()
  } else {
    notify((r.data && r.data.error) || '删除失败', 'error')
  }
}

export function importAlgoSeed(row) {
  if (!row) { notify('请先选中一条种子', 'warn'); return }
  let params = {}
  try { params = JSON.parse(row.params || '{}') } catch (e) { params = {} }
  const form = document.querySelector(`.algo-form[data-algo-id="${platform.activeAlgoId}"]`)
  if (form) {
    Object.entries(params).forEach(([k, v]) => {
      const el = form.querySelector(`input[data-key="${k}"]`)
      if (el) el.value = v
    })
  }
  platform.algoSeeds[platform.activeAlgoId] = row.seed_number
  notify('已导入种子 ' + row.seed_number + ' 的参数', 'success')
}

export async function saveCurrentParamsAsSeed() {
  const params = collectFormParams(platform.activeAlgoId)
  const r = await api.addAlgoSeed({ algorithm_id: platform.activeAlgoId, params })
  if (r.data && r.data.ok) {
    platform.algoSeeds[platform.activeAlgoId] = r.data.seed_number
    notify('参数已保存为种子，种子号: ' + r.data.seed_number, 'success')
  } else {
    notify((r.data && r.data.error) || '保存失败', 'error')
  }
}

// 供组件复用的校验工具
export { getInvalidCityLines, getMissingEdges }
