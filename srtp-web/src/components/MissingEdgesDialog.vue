<template>
  <div class="modal-mask" @click.self="$emit('cancel')">
    <div class="modal" style="width:460px">
      <div class="modal-header">
        <h3>权重不全</h3>
        <button class="close" @click="$emit('cancel')">×</button>
      </div>
      <div class="modal-body">
        <p>还有 <b>{{ edges.length }}</b> 条有向权重未填写，可补充后一并保存：</p>
        <div v-for="(e, i) in edges" :key="i" style="margin-bottom:6px;display:flex;align-items:center;gap:8px">
          <span style="width:70px">{{ e.from }}-&gt;{{ e.to }}:</span>
          <input v-model="values[i]" style="width:100px;padding:4px 6px;border:1px solid #ccc;border-radius:3px" placeholder="权重" />
        </div>
        <div class="help-text" style="margin-top:8px">提示：留空表示不补充该项，仅保存已填写的权重。</div>
      </div>
      <div class="modal-footer">
        <button class="btn" @click="$emit('cancel')">取消</button>
        <button class="btn primary" @click="apply">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive } from 'vue'

const props = defineProps({ edges: { type: Array, default: () => [] } })
const emit = defineEmits(['apply', 'cancel'])

const values = reactive({})

function apply() {
  const map = {}
  props.edges.forEach((e, i) => {
    const v = (values[i] || '').trim()
    if (v !== '') map[e.from + '->' + e.to] = v
  })
  emit('apply', map)
}
</script>
