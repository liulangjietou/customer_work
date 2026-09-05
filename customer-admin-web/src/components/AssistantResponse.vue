<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { CircleCheck, CopyDocument, Loading, WarningFilled } from '@element-plus/icons-vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import type { TraceNode } from '@/utils/traceTimeline'

const props = withDefaults(
  defineProps<{
    nodes: TraceNode[]
    text: string
    active: boolean
    failed?: boolean
    error?: string
    showTrace?: boolean
  }>(),
  { failed: false, showTrace: true },
)

const emit = defineEmits<{ inspect: [] }>()
const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | null = null

onBeforeUnmount(() => {
  if (copiedTimer) clearTimeout(copiedTimer)
})

const resultTitle = computed(() => {
  if (props.failed) return '本轮未完成'
  if (props.active) return props.text ? '正在生成结果' : '正在整理结果'
  return '最终结果'
})

async function copyResult() {
  if (!props.text) return
  try {
    await navigator.clipboard.writeText(props.text)
    copied.value = true
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => {
      copied.value = false
    }, 1600)
  } catch {
    ElMessage.error('复制失败，请手动选择内容复制')
  }
}
</script>

<template>
  <article class="assistant-response" :class="{ 'is-active': active, 'is-failed': failed }">
    <TraceTimeline
      v-if="showTrace && nodes.length > 0"
      :nodes="nodes"
      :active="active"
      :failed="failed"
    />

    <button
      v-else-if="!showTrace && nodes.length"
      type="button"
      class="trace-receipt"
      @click="emit('inspect')"
    >
      <el-icon><Loading v-if="active" class="is-loading" /><CircleCheck v-else /></el-icon>
      {{ active ? '正在执行任务' : '查看执行记录' }}<span aria-hidden="true">↗</span>
    </button>
    <div v-else-if="active" class="connecting-state" role="status" aria-live="polite">
      <span class="connecting-icon" aria-hidden="true">
        <el-icon class="is-loading"><Loading /></el-icon>
      </span>
      <span>
        <strong>正在连接智能体</strong>
        <small>即将显示完整思考与执行过程</small>
      </span>
    </div>

    <section v-if="text || active || failed" class="result-section" aria-label="智能体回答">
      <header class="result-header">
        <span class="result-status" aria-hidden="true">
          <el-icon v-if="failed"><WarningFilled /></el-icon>
          <el-icon v-else-if="active" class="is-loading"><Loading /></el-icon>
          <el-icon v-else><CircleCheck /></el-icon>
        </span>
        <strong>{{ resultTitle }}</strong>
        <span v-if="active" class="live-label">实时输出</span>
        <button
          v-if="text"
          type="button"
          class="copy-action"
          :aria-label="copied ? '已复制回答' : '复制回答'"
          @click="copyResult"
        >
          <el-icon><CopyDocument /></el-icon>
          <span>{{ copied ? '已复制' : '复制' }}</span>
        </button>
      </header>

      <div v-if="error" class="response-error" role="alert">
        {{ error }}<small v-if="text">已保留生成的内容，可补充要求后再次发送。</small>
      </div>
      <MarkdownRenderer v-if="text" :text="text" variant="answer" />
      <div v-else-if="active" class="result-placeholder" role="status" aria-live="polite">
        <span></span><span></span><span></span>
        <small>正在生成清晰、可执行的结果</small>
      </div>
      <p v-else-if="failed && !error" class="failed-empty">
        本轮未返回可展示的结果，请根据上方过程信息重试。
      </p>
    </section>

    <div class="response-extras">
      <slot />
    </div>
  </article>
</template>

<style scoped>
.response-error {
  padding: 12px 14px;
  margin: 12px 0;
  border: 1px solid var(--cw-danger);
  border-radius: 7px;
  color: var(--cw-danger);
  background: var(--cw-paper);
  font-size: 13px;
  line-height: 1.7;
}
.response-error small {
  display: block;
  color: var(--cw-text-muted);
  font-size: 12px;
  margin-top: 5px;
}

.assistant-response {
  --response-accent: var(--theme-primary, var(--el-color-primary));
  position: relative;
  width: min(100%, 880px);
  min-width: 0;
  box-sizing: border-box;
  padding: 0 0 2px;
  color: var(--el-text-color-primary);
}

.trace-receipt {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 7px 10px;
  border: 1px solid var(--cw-line);
  border-radius: 6px;
  background: var(--cw-canvas);
  color: var(--cw-text-muted);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.trace-receipt:hover {
  color: var(--cw-cobalt);
  border-color: var(--cw-cobalt);
}

.assistant-response.is-failed {
  --response-accent: var(--el-color-danger);
}

.connecting-state {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px 14px;
  color: var(--el-text-color-primary);
  background: color-mix(
    in srgb,
    var(--theme-primary, var(--el-color-primary)) 6%,
    var(--el-bg-color)
  );
  border: 1px solid
    color-mix(
      in srgb,
      var(--theme-primary, var(--el-color-primary)) 20%,
      var(--el-border-color-lighter)
    );
  border-radius: 12px;
}

.connecting-icon,
.result-status {
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  color: var(--theme-primary, var(--el-color-primary));
}

.connecting-icon {
  width: 26px;
  height: 26px;
  background: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 13%, transparent);
  border-radius: 50%;
}

.connecting-state > span:last-child {
  display: grid;
  gap: 2px;
}

.connecting-state strong {
  font-size: 13px;
  font-weight: 600;
}

.connecting-state small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.result-section {
  padding: 2px 2px 0 0;
}

.result-header {
  display: flex;
  align-items: center;
  min-height: 28px;
  gap: 8px;
  margin-bottom: 8px;
}

.result-header > strong {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.01em;
}

.result-status {
  color: var(--el-color-success);
  font-size: 15px;
}

.is-active .result-status {
  color: var(--theme-primary, var(--el-color-primary));
}

.is-failed .result-status {
  color: var(--el-color-danger);
}

.live-label {
  padding: 2px 7px;
  color: var(--theme-primary, var(--el-color-primary));
  background: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 11%, transparent);
  border-radius: 999px;
  font-size: 11px;
}

.copy-action {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  padding: 5px 8px;
  gap: 5px;
  color: var(--el-text-color-secondary);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}

.copy-action:hover {
  color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-lighter);
}

.copy-action:focus-visible {
  outline: 2px solid var(--theme-primary, var(--el-color-primary));
  outline-offset: 2px;
}

.result-placeholder {
  display: flex;
  align-items: center;
  min-height: 28px;
  gap: 4px;
  color: var(--el-text-color-secondary);
}

.result-placeholder > span {
  width: 5px;
  height: 5px;
  background: var(--theme-primary, var(--el-color-primary));
  border-radius: 50%;
  animation: result-pulse 1.15s ease-in-out infinite;
}

.result-placeholder > span:nth-child(2) {
  animation-delay: 0.12s;
}

.result-placeholder > span:nth-child(3) {
  animation-delay: 0.24s;
}

.result-placeholder small {
  margin-left: 5px;
  font-size: 12px;
}

.failed-empty {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 13px;
}

.response-extras:empty {
  display: none;
}

.response-extras:not(:empty) {
  margin-top: 12px;
}

@keyframes result-pulse {
  0%,
  60%,
  100% {
    opacity: 0.28;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

@media (max-width: 640px) {
  .assistant-response {
    padding-left: 18px;
  }

  .copy-action span,
  .live-label {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .result-placeholder > span {
    animation: none;
    opacity: 0.7;
  }

  :deep(.is-loading) {
    animation: none;
  }
}
</style>
