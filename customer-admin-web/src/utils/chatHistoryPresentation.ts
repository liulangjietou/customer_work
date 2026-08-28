/** 历史会话展示层所需的最小数据契约。 */
export interface ChatHistoryPresentableSession {
  sessionId: string
  preview: string
  lastMessageTime: string | null
  streaming?: boolean
}

/** 日期分组只组织展示顺序，不复制或改写会话对象。 */
export interface ChatHistoryDateGroup<T extends ChatHistoryPresentableSession> {
  key: string
  label: string
  sessions: T[]
}

const LOCAL_DATE_TIME_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?$/
const OFFSET_DATE_TIME_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})$/
const MILLISECOND_SUFFIX_PATTERN = /\.\d{1,9}(?=(?:Z|[+-]\d{2}:?\d{2})?$)/

/**
 * 仅筛选调用方已经加载到内存的会话。返回新数组，确保搜索不会改写原列表或分页状态。
 */
export function filterLoadedChatSessions<T extends ChatHistoryPresentableSession>(
  sessions: readonly T[],
  query: string,
): T[] {
  const keyword = query.trim().toLocaleLowerCase()
  if (!keyword) {
    return [...sessions]
  }
  return sessions.filter((session) => session.preview.toLocaleLowerCase().includes(keyword))
}

/**
 * 按演示稿把会话分成「进行中 / 今天 / 具体日期 / 未记录时间」。now 由调用方传入，便于确定性测试。
 */
export function groupChatHistorySessions<T extends ChatHistoryPresentableSession>(
  sessions: readonly T[],
  now: Date,
): ChatHistoryDateGroup<T>[] {
  const groups = new Map<string, ChatHistoryDateGroup<T>>()

  for (const session of sessions) {
    const descriptor = describeDateGroup(session, now)
    const group = groups.get(descriptor.key)
    if (group) {
      group.sessions.push(session)
      continue
    }
    groups.set(descriptor.key, {
      ...descriptor,
      sessions: [session],
    })
  }

  return Array.from(groups.values())
}

/** 后端时间可能带毫秒；侧栏只展示到分钟，避免无意义精度制造视觉噪声。 */
export function formatChatHistoryTime(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const parsed = parseChatHistoryTime(value)
  if (!parsed) {
    return value.trim().replace(MILLISECOND_SUFFIX_PATTERN, '')
  }
  return `${padTwoDigits(parsed.getHours())}:${padTwoDigits(parsed.getMinutes())}`
}

function describeDateGroup(
  session: ChatHistoryPresentableSession,
  now: Date,
): Pick<ChatHistoryDateGroup<ChatHistoryPresentableSession>, 'key' | 'label'> {
  if (session.streaming) {
    return { key: 'streaming', label: '进行中' }
  }

  const date = parseChatHistoryTime(session.lastMessageTime)
  if (!date) {
    return { key: 'undated', label: '未记录时间' }
  }

  const year = date.getFullYear()
  const month = date.getMonth() + 1
  const day = date.getDate()
  const dateKey = `${year}-${padTwoDigits(month)}-${padTwoDigits(day)}`
  if (isSameLocalDate(date, now)) {
    return { key: dateKey, label: '今天' }
  }
  if (year === now.getFullYear()) {
    return { key: dateKey, label: `${month} 月 ${day} 日` }
  }
  return { key: dateKey, label: `${year} 年 ${month} 月 ${day} 日` }
}

/**
 * 后端 LocalDateTime 没有时区，按浏览器本地时间解析；带 Z/offset 的 ISO 时间交给 Date 做时区换算。
 */
function parseChatHistoryTime(value: string | null | undefined): Date | null {
  if (!value) {
    return null
  }
  const text = value.trim()
  const localMatch = LOCAL_DATE_TIME_PATTERN.exec(text)
  if (localMatch) {
    const [, yearText, monthText, dayText, hourText = '0', minuteText = '0', secondText = '0', fraction = ''] =
      localMatch
    const year = Number(yearText)
    const monthIndex = Number(monthText) - 1
    const day = Number(dayText)
    const hour = Number(hourText)
    const minute = Number(minuteText)
    const second = Number(secondText)
    const millisecond = Number(fraction.padEnd(3, '0').slice(0, 3) || '0')
    const parsed = new Date(year, monthIndex, day, hour, minute, second, millisecond)
    if (
      parsed.getFullYear() === year &&
      parsed.getMonth() === monthIndex &&
      parsed.getDate() === day &&
      parsed.getHours() === hour &&
      parsed.getMinutes() === minute &&
      parsed.getSeconds() === second
    ) {
      return parsed
    }
    return null
  }

  if (!OFFSET_DATE_TIME_PATTERN.test(text)) {
    return null
  }
  const timestamp = Date.parse(text)
  return Number.isNaN(timestamp) ? null : new Date(timestamp)
}

function isSameLocalDate(left: Date, right: Date): boolean {
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  )
}

function padTwoDigits(value: number): string {
  return String(value).padStart(2, '0')
}
