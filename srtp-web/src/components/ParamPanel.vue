<template>
  <div class="section">
    <h3>
      算法参数
      <span style="font-size:12px;color:#999;font-weight:normal">
        — {{ activeLabel }}<template v-if="seedNum > 0"> [种子:{{ seedNum }}]</template>
      </span>
    </h3>

    <div class="algo-tabs">
      <button
        v-for="(def, id) in platform.algoParamDefs"
        :key="id"
        class="algo-tab"
        :class="{ active: id === platform.activeAlgoId }"
        @click="setActive(id)">
        {{ def.label }}
      </button>
    </div>

    <div
      v-for="(def, id) in platform.algoParamDefs"
      :key="id"
      class="algo-form"
      :class="{ active: id === platform.activeAlgoId }"
      :data-algo-id="id">
      <p v-if="!def.params || def.params.length === 0" class="algo-desc">该算法无可调参数。</p>
      <div v-else class="params-grid">
        <div class="param-item" v-for="p in def.params" :key="p.key">
          <label>{{ p.label }}</label>
          <input type="text" :data-key="p.key" :data-type="p.type" :value="p.default != null ? p.default : ''" @input="onParamInput(id)" />
        </div>
      </div>
    </div>

    <div style="margin-top:8px">
      <button class="btn small" @click="resetDefaultParams">恢复推荐参数</button>
      <button v-if="hasParams" class="btn small" @click="$emit('open-import-seed')">导入种子</button>
      <button v-if="hasParams" class="btn small" @click="$emit('save-seed')">保存种子</button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { platform, setActiveAlgo, resetDefaultParams } from '../store/platform'

defineEmits(['open-import-seed', 'save-seed'])

function setActive(id) { setActiveAlgo(id) }

const activeDef = computed(() => platform.algoParamDefs[platform.activeAlgoId])
const activeLabel = computed(() => (activeDef.value ? activeDef.value.label : ''))
const hasParams = computed(() => activeDef.value && activeDef.value.params && activeDef.value.params.length > 0)
const seedNum = computed(() => platform.algoSeeds[platform.activeAlgoId] || 0)

function onParamInput(id) {
  platform.algoSeeds[id] = 0
}
</script>
