<script setup lang="ts">
const emit = defineEmits<{
  newSession: []
}>()
</script>

<template>
  <button
    type="button"
    class="new-session-btn"
    @click="emit('newSession')"
  >
    <el-icon aria-hidden="true"><Plus /></el-icon>
    新建会话
  </button>
</template>

<style scoped>
.new-session-btn {
  min-width: 106px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 14px;
  border: 1px solid var(--theme-primary-solid, var(--el-color-primary));
  border-radius: 10px;
  background: var(--theme-primary-solid, var(--el-color-primary));
  color: var(--cw-on-primary);
  box-shadow: 0 4px 10px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 25%, transparent);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: background-color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
}

.new-session-btn:hover {
  background: var(--theme-primary-solid-hover, var(--theme-primary-solid));
  box-shadow: 0 6px 14px color-mix(in srgb, var(--theme-primary-solid, var(--el-color-primary)) 32%, transparent);
  transform: translateY(-1px);
}

.new-session-btn:active {
  background: var(--theme-primary-solid-active, var(--theme-primary-solid));
  transform: translateY(0);
}

.new-session-btn:focus-visible {
  outline: 0;
  box-shadow:
    0 0 0 2px var(--el-bg-color),
    0 0 0 4px var(--el-text-color-primary);
}

@media (prefers-reduced-motion: reduce) {
  .new-session-btn {
    transition: none;
  }

  .new-session-btn:hover {
    transform: none;
  }
}
</style>
