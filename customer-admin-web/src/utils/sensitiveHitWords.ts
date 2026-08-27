export interface SensitiveHitWordSummary {
  word: string
  /** 除首次命中外的重复次数；0 表示只命中一次。 */
  extraCount: number
}

/**
 * 按首次出现顺序汇总同一条明细内的命中词。
 *
 * 后端保留每一次实际命中用于 hitCount 与统计，前端只在展示层折叠重复标签。
 */
export function summarizeSensitiveHitWords(words: readonly string[]): SensitiveHitWordSummary[] {
  const summaries = new Map<string, SensitiveHitWordSummary>()
  for (const word of words) {
    const existing = summaries.get(word)
    if (existing) {
      existing.extraCount += 1
    } else {
      summaries.set(word, { word, extraCount: 0 })
    }
  }
  return [...summaries.values()]
}
