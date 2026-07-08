<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ text: string; active: boolean }>()

const expanded = ref(false)
</script>

<template>
  <div class="thinking-block">
    <div class="thinking-header" @click="expanded = !expanded">
      <el-icon class="chevron" :class="{ expanded }"><ArrowRight /></el-icon>
      <span>{{ active ? '思考中…' : '思考过程' }}</span>
    </div>
    <div v-show="expanded" class="thinking-content">{{ text }}</div>
  </div>
</template>

<style scoped>
.thinking-block {
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}

.thinking-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
}

.chevron {
  transition: transform 0.15s;
  font-size: 12px;
}

.chevron.expanded {
  transform: rotate(90deg);
}

.thinking-content {
  margin-top: 4px;
  padding: 8px 10px;
  background: #fafafa;
  border-left: 2px solid #dcdfe6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #666;
}
</style>
