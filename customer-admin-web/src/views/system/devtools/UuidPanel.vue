<script setup lang="ts">
import { computed, ref } from 'vue'
import { generateUuid } from '@/utils/uuid'
import { usePersistedRef } from './composables/useToolStorage'
import CopyButton from './CopyButton.vue'

// UUID 生成带随机性，按验收要求用显式"重新生成"按钮，不接自动 debounce 计算
const count = usePersistedRef('uuid:count', 5)
const noDash = usePersistedRef('uuid:noDash', false)
const uppercase = usePersistedRef('uuid:uppercase', false)

const uuids = ref<string[]>([])

function format(raw: string): string {
  const value = noDash.value ? raw.replace(/-/g, '') : raw
  return uppercase.value ? value.toUpperCase() : value
}

function regenerate() {
  uuids.value = Array.from({ length: count.value }, () => format(generateUuid()))
}

const joined = computed(() => uuids.value.join('\n'))

regenerate()
</script>

<template>
  <div class="uuid-panel">
    <el-form label-width="90px" inline>
      <el-form-item label="数量">
        <el-input-number v-model="count" :min="1" :max="20" />
      </el-form-item>
      <el-form-item label="去横线">
        <el-switch v-model="noDash" />
      </el-form-item>
      <el-form-item label="大写">
        <el-switch v-model="uppercase" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="regenerate">重新生成</el-button>
      </el-form-item>
    </el-form>

    <div class="uuid-output">
      <div class="output-header">
        <span>生成结果（{{ uuids.length }} 条）</span>
        <CopyButton :text="joined" label="全部 UUID" />
      </div>
      <div class="uuid-list">
        <div v-for="(uuid, idx) in uuids" :key="idx" class="uuid-row">
          <code class="uuid-value">{{ uuid }}</code>
          <CopyButton :text="uuid" label="UUID" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.uuid-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.uuid-output {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
}

.output-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.uuid-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.uuid-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
}

.uuid-value {
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
</style>
