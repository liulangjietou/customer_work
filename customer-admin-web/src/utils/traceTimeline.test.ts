import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ANSWER_KIND,
  SUBAGENT_MARKER_KIND,
  appendChatStreamNode,
  parseChatStreamPayload,
  parseToolResult,
  summarizeTrace,
  visibleTraceNodes,
  type TraceNode,
} from './traceTimeline'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('parseChatStreamPayload', () => {
  it('完整保留主 Agent 思考增量中的空格和换行', () => {
    const raw = '  先确认调用链\n再检查下游契约  '

    expect(parseChatStreamPayload(raw)).toEqual({
      text: raw,
      source: null,
      subagentName: null,
    })
  })

  it('解析带来源的子 Agent JSON 载荷', () => {
    expect(parseChatStreamPayload('{"text":"分析数据库","source":"main/db","subagentName":"数据库专家"}'))
      .toEqual({ text: '分析数据库', source: 'main/db', subagentName: '数据库专家' })
  })
})

describe('appendChatStreamNode', () => {
  it('合并连续思考增量并记录步骤起止时间', () => {
    vi.spyOn(Date, 'now').mockReturnValueOnce(1_000).mockReturnValueOnce(1_280)
    const nodes: TraceNode[] = []

    appendChatStreamNode(nodes, 'thinking', '先定位')
    appendChatStreamNode(nodes, 'thinking', '，再验证')

    expect(nodes).toEqual([
      { kind: 'thinking', text: '先定位，再验证', createdAt: 1_000, updatedAt: 1_280 },
    ])
  })

  it('把子 Agent 全部过程归入独立轨迹并保留最终产出', () => {
    vi.spyOn(Date, 'now')
      .mockReturnValueOnce(1_000)
      .mockReturnValueOnce(1_100)
      .mockReturnValueOnce(1_200)
      .mockReturnValueOnce(1_300)
      .mockReturnValueOnce(1_400)
    const nodes: TraceNode[] = []

    appendChatStreamNode(nodes, 'subagent_start', '数据库专家', 'main/db', '数据库专家')
    appendChatStreamNode(nodes, 'thinking', '检查', 'main/db', '数据库专家')
    appendChatStreamNode(nodes, 'thinking', '表结构', 'main/db', '数据库专家')
    appendChatStreamNode(nodes, 'tool_builtin', 'describe_table', 'main/db', '数据库专家')
    appendChatStreamNode(nodes, 'subagent_result', '结构一致', 'main/db', '数据库专家')

    expect(nodes).toHaveLength(1)
    expect(nodes[0].kind).toBe(SUBAGENT_MARKER_KIND)
    expect(nodes[0].subagent).toMatchObject({
      source: 'main/db',
      name: '数据库专家',
      status: 'done',
      expanded: false,
      startedAt: 1_000,
      updatedAt: 1_400,
      nodes: [
        { kind: 'thinking', text: '检查表结构', createdAt: 1_100, updatedAt: 1_200 },
        { kind: 'tool_builtin', text: 'describe_table', createdAt: 1_300, updatedAt: 1_300 },
        { kind: 'subagent_result', text: '结构一致', createdAt: 1_400, updatedAt: 1_400 },
      ],
    })
    expect(summarizeTrace(nodes)).toEqual({
      stepCount: 4,
      toolCount: 1,
      subagentCount: 1,
      durationMs: 400,
    })
  })

  it('把子 Agent 正文作为 ANSWER 节点保留在嵌套过程里', () => {
    const nodes: TraceNode[] = []

    appendChatStreamNode(nodes, ANSWER_KIND, '第一段', 'main/writer', '文档专家')
    appendChatStreamNode(nodes, ANSWER_KIND, '第二段', 'main/writer', '文档专家')

    expect(nodes[0].subagent?.nodes).toHaveLength(1)
    expect(nodes[0].subagent?.nodes[0].text).toBe('第一段第二段')
  })
})

describe('轨迹展示模型', () => {
  it('隐藏生命周期边界的重复文案，但不删除原始事件', () => {
    const nodes: TraceNode[] = [
      { kind: 'thinking_start', text: '开始思考', createdAt: 100, updatedAt: 100 },
      { kind: 'thinking', text: '分析调用链', createdAt: 120, updatedAt: 220 },
      { kind: 'thinking_end', text: '结束思考', createdAt: 240, updatedAt: 240 },
    ]

    expect(visibleTraceNodes(nodes)).toEqual([nodes[1]])
    expect(nodes).toHaveLength(3)
    expect(summarizeTrace(nodes)).toEqual({
      stepCount: 1,
      toolCount: 0,
      subagentCount: 0,
      durationMs: 140,
    })
  })

  it('从稳定协议中拆出工具名，完整保留多行结果', () => {
    expect(parseToolResult('工具「read_file」返回：第一行\n第二行')).toEqual({
      toolName: 'read_file',
      output: '第一行\n第二行',
    })
    expect(parseToolResult('旧协议原文')).toEqual({ toolName: null, output: '旧协议原文' })
  })
})
