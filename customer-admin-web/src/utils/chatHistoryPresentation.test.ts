import { describe, expect, it } from 'vitest'
import {
  filterLoadedChatSessions,
  formatChatHistoryTime,
  groupChatHistorySessions,
  type ChatHistoryPresentableSession,
} from './chatHistoryPresentation'

function session(
  sessionId: string,
  preview: string,
  lastMessageTime: string | null,
  streaming = false,
): ChatHistoryPresentableSession {
  return { sessionId, preview, lastMessageTime, streaming }
}

describe('filterLoadedChatSessions', () => {
  it('只在已加载会话中按标题筛选，且不改写原数组', () => {
    const loaded = [
      session('session-1', 'Java 8 CompletableFuture 异常处理', '2026-08-28 08:21:00.000'),
      session('session-2', '查询本周考勤', '2026-08-26 16:48:17.856'),
    ]

    const filtered = filterLoadedChatSessions(loaded, '  completablefuture ')

    expect(filtered.map((item) => item.sessionId)).toEqual(['session-1'])
    expect(loaded.map((item) => item.sessionId)).toEqual(['session-1', 'session-2'])
    expect(filterLoadedChatSessions(loaded, '')).not.toBe(loaded)
  })
})

describe('groupChatHistorySessions', () => {
  it('按进行中、今天、具体日期和无时间分组并维持组内顺序', () => {
    const now = new Date(2026, 7, 28, 12, 0, 0)
    const loaded = [
      session('streaming-1', '生成代码', null, true),
      session('today-1', '今天第一条', '2026-08-28 08:34:29.409'),
      session('today-2', '今天第二条', '2026-08-28 08:21:00.000'),
      session('older-1', '前天', '2026-08-26 20:23:36.200'),
      session('undated-1', '尚未落库', null),
    ]

    const groups = groupChatHistorySessions(loaded, now)

    expect(groups.map((group) => ({
      key: group.key,
      label: group.label,
      sessionIds: group.sessions.map((item) => item.sessionId),
    }))).toEqual([
      { key: 'streaming', label: '进行中', sessionIds: ['streaming-1'] },
      { key: '2026-08-28', label: '今天', sessionIds: ['today-1', 'today-2'] },
      { key: '2026-08-26', label: '8 月 26 日', sessionIds: ['older-1'] },
      { key: 'undated', label: '未记录时间', sessionIds: ['undated-1'] },
    ])
  })

  it('跨年日期包含年份，now 由调用方显式传入', () => {
    const groups = groupChatHistorySessions(
      [session('last-year', '去年会话', '2025-12-31 23:59:59.999')],
      new Date(2026, 0, 1, 0, 1, 0),
    )

    expect(groups[0]?.label).toBe('2025 年 12 月 31 日')
  })
})

describe('formatChatHistoryTime', () => {
  it('本地 LocalDateTime 去掉秒和毫秒，只保留时分', () => {
    expect(formatChatHistoryTime('2026-08-28 08:34:29.409')).toBe('08:34')
    expect(formatChatHistoryTime('2026-08-28T16:07:03')).toBe('16:07')
  })

  it('空值不展示，无法解析时也不泄漏毫秒精度', () => {
    expect(formatChatHistoryTime(null)).toBe('')
    expect(formatChatHistoryTime('业务时间.123')).toBe('业务时间')
  })

  it('带时区的 ISO 时间按浏览器本地时区展示', () => {
    const value = '2026-08-28T00:30:00+08:00'
    const expected = new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date(value))

    expect(formatChatHistoryTime(value)).toBe(expected)
    expect(groupChatHistorySessions([session('offset', '时区会话', value)], new Date(value))[0]?.label).toBe('今天')
  })

  it('非法日期和时间不会被归入真实日期组', () => {
    const groups = groupChatHistorySessions(
      [
        session('invalid-date', '非法日期', '2026-02-30 12:00:00.123'),
        session('invalid-hour', '非法时间', '2026-08-28 25:00:00.456'),
      ],
      new Date(2026, 7, 28),
    )

    expect(groups).toHaveLength(1)
    expect(groups[0]?.key).toBe('undated')
    expect(groups[0]?.sessions.map((item) => item.sessionId)).toEqual(['invalid-date', 'invalid-hour'])
    expect(formatChatHistoryTime('2026-02-30 12:00:00.123')).toBe('2026-02-30 12:00:00')
    expect(formatChatHistoryTime('2026-08-28 25:00:00.456')).toBe('2026-08-28 25:00:00')
  })
})
