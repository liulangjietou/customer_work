/**
 * 时间戳 <-> 日期时间字符串的时区换算，纯用浏览器内置 Intl.DateTimeFormat 实现，不引入日期库。
 *
 * 核心难点：JS 原生 Date 只认识"本机系统时区"和"UTC"两种输入语境，没有内置办法把
 * "2026-07-21 10:00:00 这个挂钟时间，按 Asia/Shanghai 时区解释"直接转换成时间戳。
 * 这里用标准技巧绕过：先把该挂钟时间当成 UTC 拼出一个"猜测时刻"，再用 Intl 把这个猜测时刻格式化到
 * 目标时区、看看格式化出来的挂钟时间和我们想要的差多少，这个差值就是目标时区在该时刻的 UTC 偏移，
 * 用它修正一次即可（时区偏移在同一时刻是恒定值，不存在收敛问题，一次修正就是精确解）。
 */

export interface DateTimeParts {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  second: number
}

/** 浏览器解析出的本地 IANA 时区名（如 Asia/Shanghai），用于"本地"选项。 */
export function resolveLocalTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    return 'UTC'
  }
}

/** 把某一时刻格式化成指定时区下的挂钟时间各字段。 */
export function getZonedParts(epochMs: number, timeZone: string): DateTimeParts {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  const map: Record<string, string> = {}
  for (const part of dtf.formatToParts(new Date(epochMs))) {
    map[part.type] = part.value
  }
  return {
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day),
    // h23 下午夜会格式化成 "24"，需要归零，否则会多算一天
    hour: map.hour === '24' ? 0 : Number(map.hour),
    minute: Number(map.minute),
    second: Number(map.second),
  }
}

/** 把"某时区下的挂钟时间"换算成 UTC 时间戳（毫秒）。 */
export function zonedPartsToEpochMs(parts: DateTimeParts, timeZone: string): number {
  const utcGuess = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second)
  const guessedZonedParts = getZonedParts(utcGuess, timeZone)
  const guessedAsUtc = Date.UTC(
    guessedZonedParts.year,
    guessedZonedParts.month - 1,
    guessedZonedParts.day,
    guessedZonedParts.hour,
    guessedZonedParts.minute,
    guessedZonedParts.second,
  )
  const offsetMs = guessedAsUtc - utcGuess
  return utcGuess - offsetMs
}

/** 把毫秒时间戳格式化成 'yyyy-MM-dd HH:mm:ss'（指定时区）。 */
export function formatEpochMs(epochMs: number, timeZone: string): string {
  const p = getZonedParts(epochMs, timeZone)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${p.year}-${pad(p.month)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}:${pad(p.second)}`
}

export interface ParsedDateTime extends DateTimeParts {
  /** 字符串里自带时区偏移（Z 或 ±HH:mm）时为 true，此时忽略"时区选择"，按自带偏移换算 */
  hasOffset: boolean
  offsetMinutes?: number
}

/** 宽容解析：支持 ISO8601（可带 T/空格分隔、可带毫秒、可带 Z/±HH:mm 偏移）、'yyyy-MM-dd HH:mm:ss'、'yyyy-MM-dd'。 */
export function parseLenientDateTime(raw: string): ParsedDateTime | null {
  const text = raw.trim()

  let m = text.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$/)
  if (m) {
    const [, y, mo, d, h, mi, s, offset] = m
    const result: ParsedDateTime = {
      year: Number(y),
      month: Number(mo),
      day: Number(d),
      hour: Number(h),
      minute: Number(mi),
      second: s ? Number(s) : 0,
      hasOffset: false,
    }
    if (offset) {
      result.hasOffset = true
      if (offset === 'Z') {
        result.offsetMinutes = 0
      } else {
        const om = offset.match(/^([+-])(\d{2}):?(\d{2})$/)
        if (om) {
          result.offsetMinutes = (om[1] === '-' ? -1 : 1) * (Number(om[2]) * 60 + Number(om[3]))
        }
      }
    }
    if (!isValidDateTime(result)) return null
    return result
  }

  m = text.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (m) {
    const [, y, mo, d] = m
    const result: ParsedDateTime = { year: Number(y), month: Number(mo), day: Number(d), hour: 0, minute: 0, second: 0, hasOffset: false }
    if (!isValidDateTime(result)) return null
    return result
  }

  return null
}

/** 基本范围校验，拦截"2026-13-40"这种格式对但数值非法的输入。 */
function isValidDateTime(p: DateTimeParts): boolean {
  if (p.month < 1 || p.month > 12) return false
  if (p.day < 1 || p.day > 31) return false
  if (p.hour > 23) return false
  if (p.minute > 59) return false
  if (p.second > 59) return false
  return true
}

/** 解析结果换算成 UTC 时间戳（毫秒）：自带偏移的按偏移算，否则按传入的时区解释挂钟时间。 */
export function parsedDateTimeToEpochMs(parsed: ParsedDateTime, timeZone: string): number {
  if (parsed.hasOffset) {
    const utcGuess = Date.UTC(parsed.year, parsed.month - 1, parsed.day, parsed.hour, parsed.minute, parsed.second)
    return utcGuess - (parsed.offsetMinutes ?? 0) * 60000
  }
  return zonedPartsToEpochMs(parsed, timeZone)
}

/** 按数字位数识别时间戳单位：10 位及以下按秒，超过 10 位按毫秒（覆盖到公元 2286 年，足够实用）。 */
export function detectTimestampUnit(raw: string): 'seconds' | 'millis' | null {
  const digits = raw.trim().replace(/^-/, '')
  if (!digits || !/^\d+$/.test(digits)) return null
  return digits.length <= 10 ? 'seconds' : 'millis'
}

/** 把时间戳字符串（按识别出的单位）转换成毫秒；输入非法返回 null。 */
export function timestampTextToEpochMs(raw: string): number | null {
  const unit = detectTimestampUnit(raw)
  if (!unit) return null
  const n = Number(raw.trim())
  if (!Number.isFinite(n)) return null
  return unit === 'seconds' ? n * 1000 : n
}
