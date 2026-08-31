<script setup lang="ts">
defineProps<{
  error: unknown
  hasStaleData: boolean
  loading: boolean
}>()

defineEmits<{
  retry: []
}>()
</script>

<template>
  <el-alert
    v-if="error"
    class="crud-load-state"
    type="error"
    :closable="false"
    show-icon
    :title="hasStaleData ? '数据更新失败，已保留上次结果' : '数据加载失败'"
    role="status"
    aria-live="polite"
  >
    <div class="crud-load-state__content">
      <span>{{ hasStaleData ? '当前内容可能已过期，请重试获取最新数据。' : '没有把错误伪装成空数据，请检查连接后重试。' }}</span>
      <el-button type="primary" plain :loading="loading" @click="$emit('retry')">重新加载</el-button>
    </div>
  </el-alert>
</template>

<style scoped>
.crud-load-state {
  margin-bottom: 14px;
  border: 1px solid color-mix(in srgb, var(--cw-danger) 36%, var(--cw-line));
}

.crud-load-state__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
}

@media (max-width: 640px) {
  .crud-load-state__content {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
