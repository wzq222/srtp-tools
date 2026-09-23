// 统一封装 fetch 请求：同源（开发由 Vite 代理转发 /api），携带 Cookie 以支撑
// Spring Security 的 JSESSIONID 会话。所有响应尝试解析为 JSON，并原样返回。

async function request(url, options = {}) {
  const opts = {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    ...options
  }
  if (opts.body !== undefined && typeof opts.body !== 'string') {
    opts.body = JSON.stringify(opts.body)
  }
  const resp = await fetch(url, opts)
  let data = null
  const text = await resp.text()
  if (text) {
    try { data = JSON.parse(text) } catch { data = text }
  }
  return { ok: resp.ok, status: resp.status, data }
}

function buildQuery(params) {
  const sp = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== '' && v !== null && v !== undefined) sp.append(k, v)
  })
  const s = sp.toString()
  return s ? '?' + s : ''
}

export const api = {
  // 认证
  getSession: () => request('/api/auth/session'),
  login: (username, password) => request('/api/auth/login', { method: 'POST', body: { username, password } }),
  register: (username, password) => request('/api/auth/register', { method: 'POST', body: { username, password } }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),

  // 算法
  listAlgorithms: () => request('/api/algorithm/list'),
  getParams: (cityCount) => request('/api/algorithm/params?cityCount=' + (cityCount || 0)),
  run: (payload) => request('/api/algorithm/run', { method: 'POST', body: payload }),

  // 种子（城市边权重）
  listSeeds: () => request('/api/seed/list'),
  seedInfo: (seed) => request('/api/seed/info?seed=' + seed),
  saveSeed: (payload) => request('/api/seed/save', { method: 'POST', body: payload }),
  deleteSeed: (seed) => request('/api/seed/delete', { method: 'POST', body: { seed } }),

  // 自定义算法
  listCustomAlgos: () => request('/api/custom-algo/list'),
  saveCustomAlgo: (payload) => request('/api/custom-algo/save', { method: 'POST', body: payload }),
  deleteCustomAlgo: (algoId) => request('/api/custom-algo/delete', { method: 'POST', body: { algo_id: algoId } }),

  // 历史
  checkDuplicate: (payload) => request('/api/history/check_duplicate', { method: 'POST', body: payload }),
  queryHistory: (params) => request('/api/history/query' + buildQuery(params)),
  deleteHistory: (ids) => request('/api/history/delete', { method: 'POST', body: { ids } }),
  refreshSeeds: () => request('/api/history/refresh_seeds', { method: 'POST' }),
  rerun: (historyIds) => request('/api/history/rerun', { method: 'POST', body: { history_ids: historyIds } }),

  // 算法参数种子
  listAlgoSeeds: (algorithmId) => request('/api/algo-seed/list?algorithmId=' + algorithmId),
  addAlgoSeed: (payload) => request('/api/algo-seed/add', { method: 'POST', body: payload }),
  updateAlgoSeed: (payload) => request('/api/algo-seed/update', { method: 'POST', body: payload }),
  deleteAlgoSeed: (id) => request('/api/algo-seed/delete', { method: 'POST', body: { id } })
}

export default api
