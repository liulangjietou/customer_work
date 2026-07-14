<script setup lang="ts">
import { ref } from 'vue'
import { CircleCheck, Connection, Cpu, MagicStick, Tools, VideoPause, VideoPlay } from '@element-plus/icons-vue'
// SUBAGENT_MARKER_KIND 的唯一定义在 utils/traceTimeline.ts（<script setup> 不允许普通值导出，
// 常量真正的“单一定义源”放在那个纯 TS 模块里，这里只是消费方）。
import { SUBAGENT_MARKER_KIND } from '@/utils/traceTimeline'

/** 单个子Agent 的嵌套执行时间线状态——运行中/已完成决定标题徽标样式与默认展开/折叠。 */
export interface SubagentBlock {
  /** 子Agent 调用链路径（如 "main/doc-writer"），同一 source 的多次调用归并进同一个面板。 */
  source: string
  /** 面板标题展示名。 */
  name: string
  /** 运行中=展开+转圈徽标；已完成（收到 SUBAGENT_RESULT）=自动折叠+对勾徽标（仍可手动展开）。 */
  status: 'running' | 'done'
  /** 该子Agent 内部的执行节点（含最后一条 SUBAGENT_RESULT 产出节点），复用本组件递归渲染。 */
  nodes: TraceNode[]
  /** 手动展开/折叠状态，由用户点击面板标题切换。 */
  expanded: boolean
}

/** 与后端 ChatNodeKind 一一对应（小写下划线形式，见 SSE event 名 node:<kind>）。 */
export interface TraceNode {
  kind: string
  text: string
  /** 仅当 kind === SUBAGENT_MARKER_KIND 时有值：承载该子Agent 的嵌套执行时间线。 */
  subagent?: SubagentBlock
}

defineProps<{
  nodes: TraceNode[]
  active: boolean
  /** 子Agent 嵌套面板内部复用本组件时传 true：隐藏自带的"思考中…/思考过程"折叠头，
   * 展开/折叠改由外层子Agent 面板标题统一控制，避免出现两层重复的折叠开关。 */
  hideHeader?: boolean
}>()

// 默认展开，不折叠（可手动收起）——跟旧版 ThinkingBlock 默认折叠正相反。
const expanded = ref(true)

const NODE_META: Record<string, { label: string; icon: unknown; type: string }> = {
  thinking_start: { label: '开始思考', icon: VideoPlay, type: 'primary' },
  thinking: { label: '思考中', icon: Cpu, type: 'info' },
  thinking_end: { label: '结束思考', icon: VideoPause, type: 'primary' },
  model_call: { label: '调用大模型', icon: Cpu, type: 'warning' },
  tool_skill: { label: '调用 Skill', icon: MagicStick, type: 'success' },
  tool_mcp: { label: '调用 MCP', icon: Connection, type: 'success' },
  tool_builtin: { label: '调用工具', icon: Tools, type: 'success' },
  tool_result: { label: '工具返回', icon: CircleCheck, type: 'success' },
  // 子Agent 内部复用的 kind：ANSWER 在主 Agent 走独立 message 事件不进时间线，
  // 但子Agent 内部回答增量复用同一个 kind 值，随嵌套面板展示。
  answer: { label: '生成回答', icon: MagicStick, type: 'success' },
  // 子Agent 最终产出，面板内最后一条时间线项。
  subagent_result: { label: '产出结果', icon: CircleCheck, type: 'success' },
}

function metaOf(kind: string) {
  return NODE_META[kind] ?? { label: kind, icon: Cpu, type: 'info' }
}
</script>

<template>
  <div class="trace-timeline">
    <div v-if="!hideHeader" class="trace-header" @click="expanded = !expanded">
      <el-icon class="chevron" :class="{ expanded }"><ArrowRight /></el-icon>
      <span>{{ active ? '思考中…' : '思考过程' }}</span>
    </div>
    <el-timeline v-show="hideHeader || expanded" class="trace-body">
      <el-timeline-item
        v-for="(node, idx) in nodes"
        :key="idx"
        :type="node.kind === SUBAGENT_MARKER_KIND ? (node.subagent?.status === 'done' ? 'success' : 'primary') : (metaOf(node.kind).type as any)"
        :icon="node.kind === SUBAGENT_MARKER_KIND ? Connection : metaOf(node.kind).icon"
        size="normal"
      >
        <!-- 子Agent 嵌套面板：标题+状态徽标+内部时间线（递归复用本组件，样式与主时间线完全一致） -->
        <template v-if="node.kind === SUBAGENT_MARKER_KIND && node.subagent">
          <div class="subagent-card">
            <div class="subagent-header" @click="node.subagent.expanded = !node.subagent.expanded">
              <el-icon class="chevron" :class="{ expanded: node.subagent.expanded }"><ArrowRight /></el-icon>
              <span class="subagent-title">子Agent · {{ node.subagent.name }}</span>
              <el-tag size="small" :type="node.subagent.status === 'running' ? 'primary' : 'success'" class="subagent-badge">
                <el-icon v-if="node.subagent.status === 'running'" class="is-loading"><Loading /></el-icon>
                <el-icon v-else><CircleCheck /></el-icon>
                {{ node.subagent.status === 'running' ? '运行中' : '已完成' }}
              </el-tag>
            </div>
            <div v-show="node.subagent.expanded" class="subagent-body">
              <TraceTimeline :nodes="node.subagent.nodes" :active="node.subagent.status === 'running'" hide-header />
            </div>
          </div>
        </template>
        <template v-else>
          <div class="trace-label">{{ metaOf(node.kind).label }}</div>
          <div v-if="node.text" class="trace-text">{{ node.text }}</div>
        </template>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<style scoped>
.trace-timeline {
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}

.trace-header {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
  margin-bottom: 4px;
}

.chevron {
  transition: transform 0.15s;
  font-size: 12px;
}

.chevron.expanded {
  transform: rotate(90deg);
}

.trace-body {
  padding: 4px 10px 0 4px;
}

.trace-body :deep(.el-timeline-item__tail) {
  border-left: 2px solid #e4e7ed;
}

.trace-body :deep(.el-timeline-item__node) {
  width: 18px;
  height: 18px;
  left: -3px;
}

.trace-body :deep(.el-timeline-item__wrapper) {
  padding-left: 20px;
}

.trace-label {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.trace-text {
  margin-top: 2px;
  padding: 6px 8px;
  background: #fafafa;
  border-left: 2px solid #dcdfe6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #666;
}

/* 子Agent 嵌套面板：复用主时间线配色（主题色边框 + 浅底），与普通节点的 trace-text 区分层级 */
.subagent-card {
  border: 1px solid var(--theme-primary-lighter, #e4e7ed);
  border-radius: 6px;
  padding: 6px 8px;
  background: var(--theme-page-bg, #fafcff);
}

.subagent-header {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.subagent-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  flex: 1;
}

.subagent-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.subagent-body {
  margin-top: 6px;
  padding-left: 4px;
  border-left: 2px dashed var(--theme-primary-lighter, #e4e7ed);
}
</style>
