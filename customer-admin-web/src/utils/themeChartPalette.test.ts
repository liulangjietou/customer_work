import { afterEach, describe, expect, it, vi } from 'vitest'
import { readThemeChartPalette, resolveThemeChartPalette } from './themeChartPalette'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('resolveThemeChartPalette', () => {
  it('读取运行时主题令牌并清理空白', () => {
    const values = new Map([
      ['--cw-chart-text', ' #edf2fb '],
      ['--cw-chart-axis', '#3b4961'],
      ['--cw-chart-grid', '#28354a'],
      ['--cw-chart-tooltip-bg', '#101a2b'],
      ['--cw-chart-tooltip-border', '#3b4961'],
      ['--cw-chart-series-1', '#806bff'],
      ['--cw-chart-series-8', '#8793a8'],
    ])

    const palette = resolveThemeChartPalette((name) => values.get(name) ?? '')

    expect(palette).toMatchObject({
      text: '#edf2fb',
      axis: '#3b4961',
      grid: '#28354a',
      tooltipBackground: '#101a2b',
      tooltipBorder: '#3b4961',
    })
    expect(palette.series[0]).toBe('#806bff')
    expect(palette.series[7]).toBe('#8793a8')
    expect(palette.series).toHaveLength(8)
  })

  it('缺失令牌时返回完整安全色盘', () => {
    const palette = resolveThemeChartPalette(() => '  ')

    expect(palette.text).toBe('#606266')
    expect(palette.tooltipBackground).toBe('#ffffff')
    expect(palette.series).toHaveLength(8)
    expect(palette.series.every(Boolean)).toBe(true)
  })
})

describe('readThemeChartPalette', () => {
  it('从根节点的计算样式读取并清理全部主题令牌', () => {
    const documentElement = { nodeName: 'HTML' }
    const values = new Map([
      ['--cw-chart-text', '  #edf2fb  '],
      ['--cw-chart-axis', '\n#3b4961\t'],
      ['--cw-chart-grid', ' #28354a '],
      ['--cw-chart-tooltip-bg', '  #101a2b'],
      ['--cw-chart-tooltip-border', '#45536b  '],
      ['--cw-chart-series-1', ' #806bff '],
      ['--cw-chart-series-2', ' #16856a '],
      ['--cw-chart-series-3', ' #b66b19 '],
      ['--cw-chart-series-4', ' #b94f73 '],
      ['--cw-chart-series-5', ' #3e63dd '],
      ['--cw-chart-series-6', ' #16778b '],
      ['--cw-chart-series-7', ' #a84f28 '],
      ['--cw-chart-series-8', ' #8793a8 '],
    ])
    const getPropertyValue = vi.fn((name: string) => values.get(name) ?? '')
    const getComputedStyle = vi.fn(() => ({ getPropertyValue }))
    vi.stubGlobal('document', { documentElement })
    vi.stubGlobal('getComputedStyle', getComputedStyle)

    const palette = readThemeChartPalette()

    expect(getComputedStyle).toHaveBeenCalledOnce()
    expect(getComputedStyle).toHaveBeenCalledWith(documentElement)
    expect(getPropertyValue.mock.calls.map(([name]) => name)).toEqual([...values.keys()])
    expect(palette).toEqual({
      text: '#edf2fb',
      axis: '#3b4961',
      grid: '#28354a',
      tooltipBackground: '#101a2b',
      tooltipBorder: '#45536b',
      series: [
        '#806bff',
        '#16856a',
        '#b66b19',
        '#b94f73',
        '#3e63dd',
        '#16778b',
        '#a84f28',
        '#8793a8',
      ],
    })
  })

  it('SSR 环境没有 document 时返回完整安全色盘', () => {
    vi.stubGlobal('document', undefined)
    vi.stubGlobal('getComputedStyle', undefined)

    expect(readThemeChartPalette()).toEqual({
      text: '#606266',
      axis: '#c9d0dc',
      grid: '#dce1ea',
      tooltipBackground: '#ffffff',
      tooltipBorder: '#dce1ea',
      series: [
        '#3e63dd',
        '#16856a',
        '#b66b19',
        '#b94f73',
        '#806bff',
        '#16778b',
        '#a84f28',
        '#8793a8',
      ],
    })
  })
})
