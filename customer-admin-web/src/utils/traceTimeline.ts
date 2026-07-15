import type { SubagentBlock, TraceNode } from '@/components/TraceTimeline.vue'

/**
 * 前端合成的伪节点类型，仅用于在时间线里占位承载某个子Agent 的嵌套执行面板，不对应任何后端
 * ChatNodeKind——真实子Agent 事件（THINKING/ANSWER/TOOL_* 等）挂在 SubagentBlock.nodes 里，
 * 不会以这个 kind 出现。定义在这个纯 TS 模块（而非 TraceTimeline.vue 的 <script setup>）里，
 * 是因为 <script setup> 不允许普通值导出，只能在这类文件里做"单一定义源"给两边消费。
 */
export const SUBAGENT_MARKER_KIND = '__subagent_panel__'

/**
 * 子Agent 协作场景下 SSE 新增的两个节点类型（对应后端新增 ChatNodeKind）：
 * SUBAGENT_START——子Agent 开始，text 为展示名；SUBAGENT_RESULT——子Agent 最终产出，text 为内容。
 */
export const SUBAGENT_START_KIND = 'subagent_start'
export const SUBAGENT_RESULT_KIND = 'subagent_result'

/**
 * 主 Agent 回答走独立的 message SSE 事件（不进时间线），但子Agent 内部的回答增量复用同一个
 * kind 值随嵌套面板展示——两处共用这一个常量，避免魔法值。
 */
export const ANSWER_KIND = 'answer'

/** 同一节点需要做"增量合并"而非各占一条时间线项的 kind：连续到达时后端未去重，前端需要拼接。 */
const MERGEABLE_KINDS: ReadonlySet<string> = new Set(['thinking', ANSWER_KIND])

/** ChatStreamChunk 的 SSE data 载荷解析结果：source/subagentName 为空代表主 Agent 自身事件。 */
export interface ChatStreamPayload {
  text: string
  source?: string | null
  subagentName?: string | null
}

/**
 * 解析 SSE data 载荷。协议约定：带 source 的子Agent 事件用 JSON 承载 { text, source, subagentName }；
 * 主 Agent 自身事件（source 缺省）向后兼容地保持纯文本，不强制要求后端把所有事件都改成 JSON。
 * JSON.parse 失败、或解析结果不是携带字符串 text 字段的对象，一律按纯文本兜底——保证旧协议/
 * 主 Agent 现有渲染完全不受影响。
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
    // 不是 JSON，走纯文本兜底（旧协议 / 主 Agent 事件的通常形态）
  }
  return { text: raw, source: null, subagentName: null }
}

/** 连续同 kind 的增量内容拼接进上一条，否则各占一条时间线项。 */
function mergeInto(nodes: TraceNode[], kind: string, text: string) {
  const last = nodes[nodes.length - 1]
  if (last && last.kind === kind && MERGEABLE_KINDS.has(kind)) {
    last.text += text
  } else {
    nodes.push({ kind, text })
  }
}

/** source 缺省展示名兜底：取路径末段（如 "main/doc-writer" -> "doc-writer"）。 */
function fallbackDisplayName(source: string): string {
  const lastSegment = source.split('/').pop()
  return lastSegment || source
}

/**
 * 把一条流式事件追加进执行轨迹时间线。
 * - 无 source（主 Agent 自身事件）：按老逻辑直接追加进顶层数组，行为与改造前完全一致。
 * - 带 source（子Agent 事件）：按 source 归并进对应的嵌套面板——面板节点在该 source 首次出现时
 *   创建并插入主时间线的当前位置，之后同 source 的事件只追加进面板内部，不再改变面板在主时间线
 *   中的位置；同一子Agent 在一轮内被多次调用（重新收到 SUBAGENT_START）时复用同一个面板，
 *   状态重新翻回"运行中"。
 */
export function appendChatStreamNode(
  nodes: TraceNode[],
  kind: string,
  text: string,
  source?: string | null,
  subagentName?: string | null,
) {
  if (!source) {
    mergeInto(nodes, kind, text)
    return
  }

  // SUBAGENT_START 的 text 就是展示名（协议约定），subagentName 字段存在时优先用字段值
  const resolvedName = subagentName || (kind === SUBAGENT_START_KIND ? text : undefined)

  let marker = nodes.find(
    (n): n is TraceNode & { subagent: SubagentBlock } =>
      n.kind === SUBAGENT_MARKER_KIND && !!n.subagent && n.subagent.source === source,
  )
  if (!marker) {
    const created: TraceNode = {
      kind: SUBAGENT_MARKER_KIND,
      text: '',
      subagent: {
        source,
        name: resolvedName || fallbackDisplayName(source),
        status: 'running',
        nodes: [],
        expanded: true,
      },
    }
    nodes.push(created)
    marker = created as TraceNode & { subagent: SubagentBlock }
  } else if (resolvedName) {
    marker.subagent.name = resolvedName
  }

  if (kind === SUBAGENT_START_KIND) {
    // 建面板/补全标题即可，不在面板内部重复展示一条"开始"节点；
    // 同一子Agent 被再次调用时把状态翻回运行中并重新展开
    marker.subagent.status = 'running'
    marker.subagent.expanded = true
    return
  }
  if (kind === SUBAGENT_RESULT_KIND) {
    mergeInto(marker.subagent.nodes, kind, text)
    marker.subagent.status = 'done'
    marker.subagent.expanded = false
    return
  }
  mergeInto(marker.subagent.nodes, kind, text)
}
