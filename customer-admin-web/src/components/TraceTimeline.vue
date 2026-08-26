<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import {
  ArrowRight,
  CircleCheck,
  Connection,
  Cpu,
  DocumentChecked,
  Loading,
  MagicStick,
  Tools,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  SUBAGENT_MARKER_KIND,
  parseToolResult,
  summarizeTrace,
  visibleTraceNodes,
  type ParsedToolResult,
  type TraceNode,
} from '@/utils/traceTimeline'

const props = withDefaults(
  defineProps<{
    nodes: TraceNode[]
    active: boolean
    failed?: boolean
    /** 子 Agent 内部递归渲染时隐藏总标题，由外层子 Agent 标题统一控制。 */
    hideHeader?: boolean
  }>(),
  { failed: false, hideHeader: false },
)

/** 用户要求完整思考过程保留，因此首次进入默认展开；完成后也不自动替用户收起。 */
const expanded = ref(true)

const displayNodes = computed(() => visibleTraceNodes(props.nodes))
const summary = computed(() => summarizeTrace(props.nodes))

const NODE_META: Record<string, { label: string; icon: Component; tone: string }> = {
  thinking: { label: '思考中', icon: Cpu, tone: 'thinking' },
  model_call: { label: '调用模型', icon: Cpu, tone: 'model' },
  tool_skill: { label: '调用 Skill', icon: MagicStick, tone: 'tool' },
  tool_mcp: { label: '调用 MCP', icon: Connection, tone: 'tool' },
  tool_builtin: { label: '调用工具', icon: Tools, tone: 'tool' },
  tool_result: { label: '工具返回', icon: CircleCheck, tone: 'result' },
  answer: { label: '生成回答', icon: MagicStick, tone: 'answer' },
  subagent_result: { label: '产出结果', icon: DocumentChecked, tone: 'result' },
}

function metaOf(kind: string) {
  return NODE_META[kind] ?? { label: kind, icon: Cpu, tone: 'thinking' }
}

function formatDuration(durationMs: number): string {
  if (durationMs < 1000) return `${durationMs}ms`
  return `${(durationMs / 1000).toFixed(durationMs < 10_000 ? 1 : 0)}s`
}

const statusTitle = computed(() => {
  if (props.failed) return '执行未完成'
  return props.active ? '正在执行' : '分析完成'
})

const summaryText = computed(() => {
  const parts: string[] = []
  if (summary.value.stepCount > 0) parts.push(`${summary.value.stepCount} 个步骤`)
  if (summary.value.toolCount > 0) parts.push(`${summary.value.toolCount} 次工具`)
  if (summary.value.subagentCount > 0) parts.push(`${summary.value.subagentCount} 个子 Agent`)
  if (!props.active && summary.value.durationMs != null && summary.value.durationMs > 0) {
    parts.push(formatDuration(summary.value.durationMs))
  }
  if (parts.length === 0) return props.active ? '正在接收完整过程' : '完整过程已保留'
  if (props.active) parts.push('持续更新')
  return parts.join(' · ')
})

function isCurrentNode(index: number): boolean {
  return props.active && index === displayNodes.value.length - 1
}

function shouldShowText(node: TraceNode): boolean {
  return !!node.text && node.kind !== 'tool_result'
}

function toolResultOf(node: TraceNode): ParsedToolResult {
  return parseToolResult(node.text)
}

function isLongToolResult(node: TraceNode): boolean {
  return toolResultOf(node).output.length > 320
}

function toolResultPreview(node: TraceNode): string {
  const output = toolResultOf(node).output
  return output.length > 320 ? `${output.slice(0, 180)}…` : output
}
</script>

<template>
  <section
    class="trace-timeline"
    :class="{ 'is-active': active, 'is-failed': failed, 'is-nested': hideHeader }"
    aria-label="思考与执行过程"
  >
    <button
      v-if="!hideHeader"
      type="button"
      class="trace-header"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span class="trace-status-icon" aria-hidden="true">
        <el-icon v-if="failed"><WarningFilled /></el-icon>
        <el-icon v-else-if="active" class="is-loading"><Loading /></el-icon>
        <el-icon v-else><CircleCheck /></el-icon>
      </span>
      <span class="trace-header-copy">
        <strong>{{ statusTitle }}</strong>
        <span>{{ summaryText }}</span>
      </span>
      <span class="trace-toggle-label">{{ expanded ? '收起过程' : '展开过程' }}</span>
      <el-icon class="trace-chevron" :class="{ expanded }"><ArrowRight /></el-icon>
    </button>

    <div v-show="hideHeader || expanded" class="trace-body">
      <ol class="trace-list">
        <li
          v-for="(node, index) in displayNodes"
          :key="index"
          class="trace-item"
          :class="[
            `trace-item--${node.kind === SUBAGENT_MARKER_KIND ? 'subagent' : metaOf(node.kind).tone}`,
            { 'is-current': isCurrentNode(index) },
          ]"
        >
          <span class="trace-rail" aria-hidden="true">
            <span class="trace-node-icon">
              <el-icon v-if="node.kind === SUBAGENT_MARKER_KIND"><Connection /></el-icon>
              <el-icon v-else><component :is="metaOf(node.kind).icon" /></el-icon>
            </span>
          </span>

          <template v-if="node.kind === SUBAGENT_MARKER_KIND && node.subagent">
            <div class="subagent-panel">
              <button
                type="button"
                class="subagent-header"
                :aria-expanded="node.subagent.expanded"
                @click="node.subagent.expanded = !node.subagent.expanded"
              >
                <span class="subagent-copy">
                  <strong>子 Agent · {{ node.subagent.name }}</strong>
                  <span>{{ node.subagent.nodes.length }} 个内部步骤</span>
                </span>
                <span class="subagent-state" :class="`is-${node.subagent.status}`">
                  <el-icon v-if="node.subagent.status === 'running'" class="is-loading"><Loading /></el-icon>
                  <el-icon v-else><CircleCheck /></el-icon>
                  {{ node.subagent.status === 'running' ? '运行中' : '已完成' }}
                </span>
                <el-icon class="trace-chevron" :class="{ expanded: node.subagent.expanded }"><ArrowRight /></el-icon>
              </button>
              <div v-show="node.subagent.expanded" class="subagent-body">
                <TraceTimeline
                  :nodes="node.subagent.nodes"
                  :active="node.subagent.status === 'running'"
                  hide-header
                />
              </div>
            </div>
          </template>

          <div v-else class="trace-node-content">
            <div class="trace-node-heading">
              <strong>{{ metaOf(node.kind).label }}</strong>
              <span v-if="isCurrentNode(index)" class="current-label">当前</span>
              <span v-if="node.kind === 'tool_result' && toolResultOf(node).toolName" class="tool-name">
                {{ toolResultOf(node).toolName }}
              </span>
            </div>

            <!-- 思考增量与子 Agent 结果完整保留，不做摘要替换。 -->
            <pre v-if="shouldShowText(node)" class="trace-text" :class="`trace-text--${metaOf(node.kind).tone}`">{{ node.text }}</pre>

            <!-- 工具结果可能很长：短结果直接展示；长结果展示预览并保留可展开的完整原文。 -->
            <template v-if="node.kind === 'tool_result'">
              <pre class="trace-text trace-text--result">{{ toolResultPreview(node) }}</pre>
              <details v-if="isLongToolResult(node)" class="tool-result-detail">
                <summary>查看完整工具结果</summary>
                <pre>{{ toolResultOf(node).output }}</pre>
              </details>
            </template>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.trace-timeline {
  --trace-accent: var(--theme-primary, var(--el-color-primary));
  --trace-surface: color-mix(in srgb, var(--trace-accent) 6%, var(--el-bg-color));
  --trace-accent-soft: color-mix(in srgb, var(--trace-accent) 14%, transparent);
  --trace-success: var(--el-color-success);
  --trace-line: color-mix(in srgb, var(--trace-accent) 20%, var(--el-border-color-lighter));
  margin-bottom: 16px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background: var(--trace-surface);
  border: 1px solid var(--trace-line);
  border-radius: 12px;
}

.trace-timeline.is-failed {
  --trace-accent: var(--el-color-danger);
}

.trace-timeline.is-nested {
  margin-bottom: 0;
  overflow: visible;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.trace-header,
.subagent-header {
  display: flex;
  align-items: center;
  width: 100%;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.trace-header {
  gap: 10px;
  padding: 12px 14px;
}

.trace-header:focus-visible,
.subagent-header:focus-visible {
  outline: 2px solid var(--trace-accent);
  outline-offset: -2px;
}

.trace-status-icon {
  display: inline-grid;
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  place-items: center;
  color: var(--trace-success);
  background: color-mix(in srgb, var(--trace-success) 14%, transparent);
  border-radius: 50%;
}

.is-active .trace-status-icon {
  color: var(--trace-accent);
  background: var(--trace-accent-soft);
}

.is-failed .trace-status-icon {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.trace-header-copy,
.subagent-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.trace-header-copy {
  flex: 1;
}

.trace-header-copy strong,
.subagent-copy strong,
.trace-node-heading strong {
  font-weight: 600;
}

.trace-header-copy > span,
.subagent-copy > span {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-toggle-label {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.trace-chevron {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  transition: transform 0.18s ease;
}

.trace-chevron.expanded {
  transform: rotate(90deg);
}

.trace-body {
  padding: 0 14px 14px;
}

.is-nested > .trace-body {
  padding: 0;
}

.trace-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  min-width: 0;
}

.trace-rail {
  position: relative;
  display: flex;
  justify-content: center;
}

.trace-item:not(:last-child) .trace-rail::after {
  position: absolute;
  top: 25px;
  bottom: -3px;
  left: 50%;
  width: 1px;
  background: var(--trace-line);
  content: '';
  transform: translateX(-50%);
}

.trace-node-icon {
  position: relative;
  z-index: 1;
  display: grid;
  width: 20px;
  height: 20px;
  margin-top: 7px;
  place-items: center;
  color: var(--trace-accent);
  background: var(--el-bg-color);
  border: 1px solid var(--trace-line);
  border-radius: 50%;
}

.trace-node-icon :deep(.el-icon) {
  font-size: 12px;
}

.trace-item--result .trace-node-icon,
.trace-item--answer .trace-node-icon {
  color: var(--trace-success);
  border-color: color-mix(in srgb, var(--trace-success) 32%, var(--el-border-color));
}

.trace-item.is-current .trace-node-icon {
  color: var(--trace-accent);
  background: color-mix(in srgb, var(--trace-accent) 10%, var(--el-bg-color));
  box-shadow: 0 0 0 4px var(--trace-accent-soft);
}

.trace-node-content,
.subagent-panel {
  min-width: 0;
  margin: 3px 0 9px 6px;
}

.trace-node-content {
  padding: 5px 8px 7px;
}

.trace-node-heading {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 8px;
  font-size: 12px;
}

.current-label,
.tool-name,
.subagent-state {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 4px;
  border-radius: 999px;
  font-size: 11px;
}

.current-label {
  padding: 2px 6px;
  color: var(--trace-accent);
  background: var(--trace-accent-soft);
}

.tool-name {
  overflow: hidden;
  max-width: 220px;
  padding: 2px 7px;
  color: var(--el-color-primary);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: var(--el-color-primary-light-9);
}

.trace-text,
.tool-result-detail pre {
  margin: 5px 0 0;
  padding: 7px 9px;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  background: color-mix(in srgb, var(--el-bg-color) 82%, transparent);
  border-left: 2px solid var(--trace-line);
  border-radius: 0 6px 6px 0;
}

.trace-text--thinking {
  color: var(--el-text-color-secondary);
}

.trace-text--result,
.trace-text--answer {
  border-left-color: color-mix(in srgb, var(--trace-success) 52%, var(--el-border-color));
}

.tool-result-detail {
  margin-top: 5px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.tool-result-detail summary {
  width: fit-content;
  cursor: pointer;
  user-select: none;
}

.tool-result-detail pre {
  max-height: 360px;
  overflow: auto;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.subagent-panel {
  overflow: hidden;
  background: color-mix(in srgb, var(--el-bg-color) 86%, transparent);
  border: 1px solid var(--trace-line);
  border-radius: 9px;
}

.subagent-header {
  gap: 8px;
  padding: 9px 10px;
}

.subagent-copy {
  flex: 1;
}

.subagent-copy strong {
  font-size: 12px;
}

.subagent-state {
  padding: 3px 7px;
  color: var(--trace-success);
  background: color-mix(in srgb, var(--trace-success) 12%, transparent);
}

.subagent-state.is-running {
  color: var(--trace-accent);
  background: var(--trace-accent-soft);
}

.subagent-body {
  padding: 7px 10px 9px;
  border-top: 1px solid var(--trace-line);
}

@media (max-width: 640px) {
  .trace-header {
    align-items: flex-start;
  }

  .trace-toggle-label {
    display: none;
  }

  .tool-name {
    max-width: 120px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .trace-chevron {
    transition: none;
  }

  :deep(.is-loading) {
    animation: none;
  }
}
</style>
