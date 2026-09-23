<template>
  <div class="modal-mask" @click.self="$emit('close')">
    <div class="modal" style="width:850px;height:680px">
      <div class="modal-header">
        <h3>{{ title }}</h3>
        <button class="close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body" style="display:flex;justify-content:center;align-items:center">
        <canvas ref="canvas" width="800" height="550" id="pathCanvas"></canvas>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'

const props = defineProps({
  title: { type: String, default: '路径图' },
  result: { type: Object, required: true } // { algorithm_label, best_path: number[] }
})
defineEmits(['close'])

const canvas = ref(null)

function drawPath(r) {
  const path = r.best_path
  if (!path || path.length === 0) return
  const cityCount = Math.max.apply(null, path) + 1
  const cv = canvas.value
  if (!cv) return
  const ctx = cv.getContext('2d')
  const w = cv.width
  const h = cv.height
  ctx.clearRect(0, 0, w, h)

  const margin = 60
  const cols = Math.ceil(Math.sqrt(cityCount))
  const rows = Math.ceil(cityCount / cols)
  const spacingX = (w - 2 * margin) / Math.max(cols - 1, 1)
  const spacingY = (h - 2 * margin) / Math.max(rows - 1, 1)
  const coords = []
  for (let i = 0; i < cityCount; i++) {
    const col = i % cols
    const row = Math.floor(i / cols)
    coords.push({ x: margin + col * spacingX, y: margin + row * spacingY })
  }

  ctx.strokeStyle = '#3498db'
  ctx.lineWidth = 2
  ctx.beginPath()
  for (let i = 0; i < path.length; i++) {
    const p = coords[path[i]]
    if (i === 0) ctx.moveTo(p.x, p.y)
    else ctx.lineTo(p.x, p.y)
  }
  if (path.length > 1) ctx.lineTo(coords[path[0]].x, coords[path[0]].y)
  ctx.stroke()

  function drawArrow(x1, y1, x2, y2) {
    const angle = Math.atan2(y2 - y1, x2 - x1)
    const arrowLen = 10
    const arrowAngle = Math.PI / 7
    const midX = x1 + (x2 - x1) * 0.55
    const midY = y1 + (y2 - y1) * 0.55
    ctx.save()
    ctx.translate(midX, midY)
    ctx.rotate(angle)
    ctx.fillStyle = '#e74c3c'
    ctx.beginPath()
    ctx.moveTo(0, 0)
    ctx.lineTo(-arrowLen, -arrowLen * 0.5)
    ctx.lineTo(-arrowLen, arrowLen * 0.5)
    ctx.closePath()
    ctx.fill()
    ctx.restore()
  }
  for (let i = 0; i < path.length; i++) {
    const a = coords[path[i]]
    const b = coords[path[(i + 1) % path.length]]
    drawArrow(a.x, a.y, b.x, b.y)
  }

  for (let i = 0; i < cityCount; i++) {
    const p = coords[i]
    ctx.beginPath()
    ctx.arc(p.x, p.y, 14, 0, 2 * Math.PI)
    ctx.fillStyle = '#e74c3c'
    ctx.fill()
    ctx.strokeStyle = '#c0392b'
    ctx.lineWidth = 2
    ctx.stroke()
    ctx.fillStyle = '#fff'
    ctx.font = '12px "Microsoft YaHei"'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(i, p.x, p.y)
  }
}

onMounted(() => { nextTick(() => drawPath(props.result)) })
watch(() => props.result, (v) => { if (v) nextTick(() => drawPath(v)) })
</script>
