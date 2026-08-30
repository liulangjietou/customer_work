import { describe, expect, it } from 'vitest'
import { diffPromptLines } from './promptLineDiff'

describe('diffPromptLines', () => {
  it('A/B 重排时保留原始顺序，并稳定标记移动的行', () => {
    expect(diffPromptLines('A\nB', 'B\nA')).toEqual({
      left: [
        { text: 'A', changed: true },
        { text: 'B', changed: false },
      ],
      right: [
        { text: 'B', changed: false },
        { text: 'A', changed: true },
      ],
    })
  })

  it('重复行按出现次数逐一匹配，不会被去重', () => {
    expect(diffPromptLines('规则\n规则\n结束', '规则\n结束')).toEqual({
      left: [
        { text: '规则', changed: false },
        { text: '规则', changed: true },
        { text: '结束', changed: false },
      ],
      right: [
        { text: '规则', changed: false },
        { text: '结束', changed: false },
      ],
    })
  })

  it('增删混合时分别标记删除行和新增行，并保留公共行', () => {
    expect(diffPromptLines('开头\n旧约束\n公共规则\n待删除', '开头\n新约束\n公共规则\n新增说明')).toEqual({
      left: [
        { text: '开头', changed: false },
        { text: '旧约束', changed: true },
        { text: '公共规则', changed: false },
        { text: '待删除', changed: true },
      ],
      right: [
        { text: '开头', changed: false },
        { text: '新约束', changed: true },
        { text: '公共规则', changed: false },
        { text: '新增说明', changed: true },
      ],
    })
  })
})
