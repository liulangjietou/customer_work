/**
 * 子 Agent 的嵌套执行轨迹。状态与节点都属于对话展示模型，不泄漏到其它业务领域。
 */
export interface SubagentBlock {
  source: string
  name: string
  status: 'running' | 'done'
  nodes: TraceNode[]
  expanded: boolean
  startedAt?: number
  updatedAt?: number
}

/** 与后端 ChatNodeKind 一一对应的小写展示节点；时间戳由前端收到 SSE 时补齐。 */
export interface TraceNode {
  kind: string
  text: string
  subagent?: SubagentBlock
  createdAt?: number
  updatedAt?: number
}

export interface TraceSummary {
  stepCount: number
  toolCount: number
  subagentCount: number
  durationMs: number | null
}

export interface ParsedToolResult {
  toolName: string | null
  output: string
}

/**
 * 前端合成的伪节点类型，仅用于在时间线里占位承载某个子Agent 的嵌套执行面板，不对应任何后端
 * ChatNodeKind——真实子Agent 事件（THINKING/ANSWER/TOOL_* 等）挂在 SubagentBlock.nodes 里。
 */
export const SUBAGENT_MARKER_KIND = '__subagent_panel__'

/** 子Agent 协作场景下 SSE 新增的两个节点类型（对应后端 ChatNodeKind）。 */
export const SUBAGENT_START_KIND = 'subagent_start'
export const SUBAGENT_RESULT_KIND = 'subagent_result'

/** 主 Agent 回答走独立 message SSE；子Agent 回答复用这个 kind 挂进嵌套面板。 */
export const ANSWER_KIND = 'answer'

/** 只承担生命周期边界，不再作为两条重复的可见步骤渲染。原始事件仍保留在 nodes 中。 */
const STRUCTURAL_KINDS: ReadonlySet<string> = new Set(['thinking_start', 'thinking_end'])

const TOOL_KINDS: ReadonlySet<string> = new Set(['tool_skill', 'tool_mcp', 'tool_builtin'])

/** 同一节点需要做增量合并而非各占一条时间线项的 kind。 */
const MERGEABLE_KINDS: ReadonlySet<string> = new Set(['thinking', ANSWER_KIND])

const TOOL_RESULT_PATTERN = /^工具「([^」]+)」返回：([\s\S]*)$/

/** ChatStreamChunk 的 SSE data 载荷解析结果：source/subagentName 为空代表主 Agent 自身事件。 */
export interface ChatStreamPayload {
  text: string
  source?: string | null
  subagentName?: string | null
}

/**
 * 解析 SSE data 载荷。带 source 的子Agent 事件用 JSON；主 Agent 保持纯文本。
 * JSON 解析失败时原样回退，保证旧协议与思考增量中的空格、换行都不丢失。
 */
export function parseChatStreamPayload(raw: string): ChatStreamPayload {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const candidate = parsed as Record<string, unknown>
      if (typeof candidate.text === 'string') {
        return {
          text: candidate.text,
          source: typeof candidate.source === 'string' ? candidate.source : null,
          subagentName: typeof candidate.subagentName === 'string' ? candidate.subagentName : null,
        }
      }
    }
  } catch {
    // 非 JSON 是主 Agent/旧协议的正常形态，原样回退。
  }
  return { text: raw, source: null, subagentName: null }
}

/** 结构边界节点仍保留在原始数组中，但不作为重复步骤展示。 */
export function visibleTraceNodes(nodes: TraceNode[]): TraceNode[] {
  return nodes.filter((node) => !STRUCTURAL_KINDS.has(node.kind))
}

/** 解析后端稳定的工具结果展示契约；不匹配时仍完整返回原文。 */
export function parseToolResult(text: string): ParsedToolResult {
  const match = text.match(TOOL_RESULT_PATTERN)
  return match ? { toolName: match[1], output: match[2] } : { toolName: null, output: text }
}

/** 汇总执行轨迹标题所需的可验证事实，不猜测 token、文件数等后端未提供的数据。 */
export function summarizeTrace(nodes: TraceNode[]): TraceSummary {
  let stepCount = 0
  let toolCount = 0
  let subagentCount = 0
  let earliest: number | null = null
  let latest: number | null = null

  const visit = (items: TraceNode[]) => {
    for (const node of items) {
      if (!STRUCTURAL_KINDS.has(node.kind)) stepCount += 1
      if (TOOL_KINDS.has(node.kind)) toolCount += 1
      if (node.kind === SUBAGENT_MARKER_KIND && node.subagent) {
        subagentCount += 1
        visit(node.subagent.nodes)
      }
      const start = node.createdAt ?? node.subagent?.startedAt
      const end = node.updatedAt ?? node.subagent?.updatedAt ?? start
      if (start != null) earliest = earliest == null ? start : Math.min(earliest, start)
      if (end != null) latest = latest == null ? end : Math.max(latest, end)
    }
  }
  visit(nodes)

  return {
    stepCount,
    toolCount,
    subagentCount,
    durationMs: earliest != null && latest != null ? Math.max(0, latest - earliest) : null,
  }
}

/** 连续同 kind 的增量内容拼接进上一条，并保留该步骤的起止时间。 */
function mergeInto(nodes: TraceNode[], kind: string, text: string, now: number) {
  const last = nodes[nodes.length - 1]
  if (last && last.kind === kind && MERGEABLE_KINDS.has(kind)) {
    last.text += text
    last.updatedAt = now
  } else {
    nodes.push({ kind, text, createdAt: now, updatedAt: now })
  }
}

/** source 缺省展示名兜底：取路径末段（如 main/doc-writer -> doc-writer）。 */
function fallbackDisplayName(source: string): string {
  const lastSegment = source.split('/').pop()
  return lastSegment || source
}

/**
 * 把一条流式事件追加进执行轨迹时间线。主 Agent 直接追加；子 Agent 按 source 归入嵌套面板。
 * 时间戳只用于本轮 UI 耗时反馈，不参与后端协议，也不会改变思考/工具文本。
 */
export function appendChatStreamNode(
  nodes: TraceNode[],
  kind: string,
  text: string,
  source?: string | null,
  subagentName?: string | null,
) {
  const now = Date.now()
  if (!source) {
    mergeInto(nodes, kind, text, now)
    return
  }

  const resolvedName = subagentName || (kind === SUBAGENT_START_KIND ? text : undefined)
  let marker = nodes.find(
    (node): node is TraceNode & { subagent: SubagentBlock } =>
      node.kind === SUBAGENT_MARKER_KIND && !!node.subagent && node.subagent.source === source,
  )
  if (!marker) {
    const created: TraceNode = {
      kind: SUBAGENT_MARKER_KIND,
      text: '',
      createdAt: now,
      updatedAt: now,
      subagent: {
        source,
        name: resolvedName || fallbackDisplayName(source),
        status: 'running',
        nodes: [],
        expanded: true,
        startedAt: now,
        updatedAt: now,
      },
    }
    nodes.push(created)
    marker = created as TraceNode & { subagent: SubagentBlock }
  } else if (resolvedName) {
    marker.subagent.name = resolvedName
  }
  marker.updatedAt = now
  marker.subagent.updatedAt = now

  if (kind === SUBAGENT_START_KIND) {
    marker.subagent.status = 'running'
    marker.subagent.expanded = true
    return
  }
  if (kind === SUBAGENT_RESULT_KIND) {
    mergeInto(marker.subagent.nodes, kind, text, now)
    marker.subagent.status = 'done'
    marker.subagent.expanded = false
    return
  }
  mergeInto(marker.subagent.nodes, kind, text, now)
}
