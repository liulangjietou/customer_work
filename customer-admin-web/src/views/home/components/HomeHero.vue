<script setup lang="ts">
import type { HomeEntry } from '../../homePresentation'

defineProps<{
  displayName: string
  todayLabel: string
  greeting: string
  primaryEntry?: HomeEntry
}>()
const emit = defineEmits<{ navigate: [path: string] }>()
</script>

<template>
  <header class="home-hero" aria-label="工作台欢迎区">
    <div>
      <p class="welcome-date">{{ todayLabel }}</p>
      <h1 id="home-title">{{ greeting }}，{{ displayName }}</h1>
      <p class="welcome-summary">继续处理任务，查看智能体运行与团队工作状态。</p>
    </div>
    <el-button
      v-if="primaryEntry"
      type="primary"
      :aria-label="`进入${primaryEntry.title}`"
      @click="emit('navigate', primaryEntry.path)"
    >
      <el-icon><Plus /></el-icon><span>开始新任务</span>
    </el-button>
  </header>
</template>

<style scoped>
.home-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 24px;
}
.welcome-date {
  margin: 0 0 9px;
  color: var(--cw-text-muted);
  font-size: 12px;
}
h1 {
  margin: 0;
  color: var(--cw-text);
  font-size: clamp(22px, 2vw, 28px);
  line-height: 1.35;
  font-weight: 650;
  letter-spacing: -0.6px;
}
.welcome-summary {
  margin: 10px 0 0;
  color: var(--cw-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.el-button {
  flex-shrink: 0;
  gap: 6px;
}
@media (max-width: 600px) {
  .home-hero {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }
}
</style>
