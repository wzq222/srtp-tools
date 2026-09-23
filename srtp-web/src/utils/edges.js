// 边权重文本解析与校验工具（移植自原 jQuery 版逻辑）

const EDGE_RE = /(\d+)\s*->\s*(\d+)\s*:\s*([\d.]+)/

export function parseMaxCityIndex(text) {
  let max = -1
  text.split('\n').forEach((line) => {
    const m = line.match(EDGE_RE)
    if (m) max = Math.max(max, parseInt(m[1], 10), parseInt(m[2], 10))
  })
  return max
}

export function parseEdges(text) {
  const cities = []
  const weights = {}
  let maxIdx = -1
  text.split('\n').forEach((line) => {
    const m = line.match(EDGE_RE)
    if (m) {
      const a = parseInt(m[1], 10)
      const b = parseInt(m[2], 10)
      const w = parseFloat(m[3])
      maxIdx = Math.max(maxIdx, a, b)
      weights[a + ',' + b] = w
    }
  })
  const n = Math.max(maxIdx + 1, 2)
  for (let i = 0; i < n; i++) cities.push({ name: String(i), x: 0, y: 0 })
  return { cities, weights, cityCount: n }
}

// 返回 [{lineNo, text, cityId}]：引用了不存在城市的边
export function getInvalidCityLines(cityData, maxCityCount) {
  const invalid = []
  const lines = cityData.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(EDGE_RE)
    if (m) {
      const from = parseInt(m[1], 10)
      const to = parseInt(m[2], 10)
      if (from >= maxCityCount || to >= maxCityCount) {
        const badCity = from >= maxCityCount ? from : to
        invalid.push({ lineNo: i + 1, text: lines[i].trim(), cityId: badCity })
      }
    }
  }
  return invalid
}

// 返回缺少的有向边列表 [{from, to}]（在 editCityCount 城市规模下）
export function getMissingEdges(cityData, cityCount) {
  const existing = {}
  cityData.split('\n').forEach((line) => {
    const m = line.match(EDGE_RE)
    if (m) existing[parseInt(m[1], 10) + '->' + parseInt(m[2], 10)] = true
  })
  const missing = []
  for (let i = 0; i < cityCount; i++) {
    for (let j = 0; j < cityCount; j++) {
      if (i === j) continue
      if (!existing[i + '->' + j]) missing.push({ from: i, to: j })
    }
  }
  return missing
}

// 随机生成 n 个城市的全有向边权重文本
export function genSeedEdges(n) {
  const lines = []
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      if (i === j) continue
      const w = (10 + Math.random() * 190).toFixed(1)
      lines.push(i + '->' + j + ':' + w)
    }
  }
  return lines.join('\n')
}
