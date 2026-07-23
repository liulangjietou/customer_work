<script setup lang="ts">
// Plan Mode 确认卡片（P1-1 HITL）：从 VibeCodingPanel 抽出的共享组件，ChatPanel 复用同一份展示/交互，
// 行为不变——批准/拒绝按钮只 emit 决策，不直接调后端接口（对话与 VibeCoding 的确认接口路径不同，
// 由各自面板决定调哪个 API）。
import type { PlanCard } from '@/utils/planCard'
import { planActionLabel, planStatusText } from '@/utils/planCard'

defineProps<{ plans: PlanCard[] }>()
const emit = defineEmits<{ decision: [card: PlanCard, approved: boolean] }>()
</script>

<template>
  <div v-if="plans && plans.length > 0" class="plan-cards">
    <div
      v-for="(plan, pi) in plans"
      :key="pi"
      class="plan-card"
      :class="`plan-card--${plan.status.toLowerCase()}`"
    >
      <div class="plan-card-header">
        <el-icon class="plan-card-icon"><Warning /></el-icon>
        <span class="plan-card-title">高风险操作待确认</span>
        <el-tag
          v-if="plan.status === 'PENDING'"
          type="warning"
          size="small"
          effect="dark"
          class="plan-card-countdown"
        >
          {{ plan.remainingSeconds }}s
        </el-tag>
        <el-tag
          v-else
          :type="plan.status === 'APPROVED' ? 'success' : 'info'"
          size="small"
          effect="dark"
        >
          {{ planStatusText(plan.status) }}
        </el-tag>
      </div>
      <p v-if="plan.reason" class="plan-card-reason">{{ plan.reason }}</p>
      <ul class="plan-card-actions">
        <li v-for="(action, ai) in plan.actions" :key="ai" class="plan-card-action">
          <el-tag size="small" class="plan-action-type">{{ planActionLabel(action.type) }}</el-tag>
          <code class="plan-action-target" :title="action.target">{{ action.target }}</code>
        </li>
      </ul>
      <div v-if="plan.status === 'PENDING'" class="plan-card-buttons">
        <el-button
          type="primary"
          size="small"
          :loading="plan.submitting"
          @click="emit('decision', plan, true)"
        >
          批准执行
        </el-button>
        <el-button
          type="danger"
          size="small"
          plain
          :disabled="plan.submitting"
          @click="emit('decision', plan, false)"
        >
          拒绝
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.plan-cards {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.plan-card {
  padding: 10px 12px;
  border-radius: 6px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-color-warning-light-9, var(--el-fill-color-light));
}

.plan-card--approved {
  border-left-color: var(--el-color-success);
  background: var(--el-color-success-light-9, var(--el-fill-color-light));
}

.plan-card--rejected,
.plan-card--timeout {
  border-left-color: var(--el-color-info);
  background: var(--el-fill-color-light);
  opacity: 0.85;
}

.plan-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.plan-card-icon {
  color: var(--el-color-warning);
}

.plan-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.plan-card-countdown {
  margin-left: auto;
}

.plan-card-header .el-tag:not(.plan-card-countdown) {
  margin-left: auto;
}

.plan-card-reason {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 6px;
}

.plan-card-actions {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}

.plan-card-action {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
}

.plan-action-type {
  flex-shrink: 0;
}

.plan-action-target {
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-card-buttons {
  display: flex;
  gap: 8px;
}
</style>
