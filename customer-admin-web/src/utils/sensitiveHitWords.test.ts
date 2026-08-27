import { describe, expect, it } from 'vitest'
import { summarizeSensitiveHitWords } from './sensitiveHitWords'

describe('summarizeSensitiveHitWords', () => {
  it('按首次出现顺序折叠重复词，并把重复次数显示为额外命中数', () => {
    expect(summarizeSensitiveHitWords(['梅西', '阿根廷', '梅西', '阿根廷', '阿根廷']))
      .toEqual([
        { word: '梅西', extraCount: 1 },
        { word: '阿根廷', extraCount: 2 },
      ])
  })

  it('只命中一次的词保留且额外次数为零', () => {
    expect(summarizeSensitiveHitWords(['梅西', '阿根廷'])).toEqual([
      { word: '梅西', extraCount: 0 },
      { word: '阿根廷', extraCount: 0 },
    ])
  })

  it('空命中词列表返回空结果', () => {
    expect(summarizeSensitiveHitWords([])).toEqual([])
  })
})
