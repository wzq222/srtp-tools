export function formatTime(ms) {
  if (ms == null) return '-'
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}

export function fmtDist(val) {
  return val ? Number(val).toFixed(4) : '-'
}

export function escapeHtml(str) {
  if (str == null) return ''
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
