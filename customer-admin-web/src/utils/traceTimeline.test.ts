import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ANSWER_KIND,
  DECISION_KIND,
  SUBAGENT_MARKER_KIND,
  appendChatStreamNode,
  decisionScoreLabel,
  decisionStatus,
  parseChatStreamPayload,
  parseDecision,
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
      decisionCount: 0,
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
      decisionCount: 0,
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

describe('Jev 决策', () => {
  const shadowEscalation = JSON.stringify({
    runMode: 'shadow',
    point: 'escalation',
    title: '情绪升级判定',
    verdict: '最可能：强烈愤怒，或明确要求转人工、要求投诉升级（档位期望 2.95 / 最高 3）',
    action: '直接转人工坐席',
    executed: false,
    confidence: 0.93,
    model: 'jev-1.13.0',
    latencyMs: 42,
    degraded: false,
  })

  it('不计入智能体的执行步骤，单独计数', () => {
    const nodes: TraceNode[] = [
      { kind: DECISION_KIND, text: shadowEscalation, createdAt: 100, updatedAt: 100 },
      { kind: 'model_call', text: '调用大模型', createdAt: 120, updatedAt: 120 },
      { kind: 'tool_builtin', text: 'queryOrder', createdAt: 140, updatedAt: 140 },
    ]

    expect(summarizeTrace(nodes)).toMatchObject({ stepCount: 2, toolCount: 1, decisionCount: 1 })
  })

  it('连续的多个决策各占一条，不像思考增量那样合并', () => {
    const nodes: TraceNode[] = []

    appendChatStreamNode(nodes, DECISION_KIND, shadowEscalation)
    appendChatStreamNode(nodes, DECISION_KIND, shadowEscalation)

    expect(nodes).toHaveLength(2)
  })

  /** 决策载荷是没有 text 字段的 JSON，SSE 解析时必须原样保留，不能被误当成子 Agent 载荷拆开。 */
  it('经过 SSE 载荷解析后原样保留，仍能解析出决策', () => {
    const payload = parseChatStreamPayload(shadowEscalation)

    expect(payload.text).toBe(shadowEscalation)
    expect(payload.source).toBeNull()
    expect(parseDecision(payload.text)?.title).toBe('情绪升级判定')
  })

  it('解析载荷；结构不合法时返回 null，由界面回退为原文展示', () => {
    expect(parseDecision(shadowEscalation)).toEqual({
      point: 'escalation',
      title: '情绪升级判定',
      verdict: '最可能：强烈愤怒，或明确要求转人工、要求投诉升级（档位期望 2.95 / 最高 3）',
      action: '直接转人工坐席',
      executed: false,
      degraded: false,
      runMode: 'shadow',
      confidence: 0.93,
      model: 'jev-1.13.0',
      latencyMs: 42,
    })
    expect(parseDecision('不是 JSON')).toBeNull()
    expect(parseDecision('{"verdict":"缺标题"}')).toBeNull()
    expect(parseDecision('[1,2]')).toBeNull()
  })

  /**
   * 影子模式下「直接转人工坐席」必须写明是线上会做的事——否则运营会以为后台真的转了人工。
   * 反过来，后台真执行的答复闸门判定「放行」也是 executed=false，不能被标成影子。
   */
  it('区分影子、已执行、未触发与未获得决策', () => {
    const base = parseDecision(shadowEscalation)!

    expect(decisionStatus(base)).toEqual({ label: '影子 · 后台未执行', tone: 'shadow', actionLabel: '线上会' })
    expect(decisionStatus({ ...base, runMode: 'live_traced', executed: true }).tone).toBe('executed')
    expect(decisionStatus({ ...base, runMode: 'live_traced', executed: false })).toEqual({
      label: '未触发',
      tone: 'idle',
      actionLabel: '处置',
    })
    expect(decisionStatus({ ...base, degraded: true }).tone).toBe('degraded')
  })

  it('是/否判定展示概率，单选与打分展示置信度', () => {
    expect(decisionScoreLabel('refund_risk')).toBe('概率')
    expect(decisionScoreLabel('payment_claim')).toBe('概率')
    expect(decisionScoreLabel('escalation')).toBe('置信度')
    expect(decisionScoreLabel('tool_scope')).toBe('置信度')
  })
})
