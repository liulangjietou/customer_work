export interface ThemeChartPalette {
  text: string
  axis: string
  grid: string
  tooltipBackground: string
  tooltipBorder: string
  series: string[]
}

type CssVariableReader = (name: string) => string

const SERIES_TOKENS = [
  '--cw-chart-series-1',
  '--cw-chart-series-2',
  '--cw-chart-series-3',
  '--cw-chart-series-4',
  '--cw-chart-series-5',
  '--cw-chart-series-6',
  '--cw-chart-series-7',
  '--cw-chart-series-8',
] as const

const SERIES_FALLBACKS = [
  '#3e63dd',
  '#16856a',
  '#b66b19',
  '#b94f73',
  '#806bff',
  '#16778b',
  '#a84f28',
  '#8793a8',
] as const

function readToken(read: CssVariableReader, name: string, fallback: string): string {
  return read(name).trim() || fallback
}

/**
 * 将运行时主题令牌转换成 ECharts 可消费的静态色值。
 * Canvas 不会像普通 DOM 一样自动响应 CSS 变量，因此每次主题变化都要重新读取并 setOption。
 */
export function resolveThemeChartPalette(read: CssVariableReader): ThemeChartPalette {
  return {
    text: readToken(read, '--cw-chart-text', '#606266'),
    axis: readToken(read, '--cw-chart-axis', '#c9d0dc'),
    grid: readToken(read, '--cw-chart-grid', '#dce1ea'),
    tooltipBackground: readToken(read, '--cw-chart-tooltip-bg', '#ffffff'),
    tooltipBorder: readToken(read, '--cw-chart-tooltip-border', '#dce1ea'),
    series: SERIES_TOKENS.map((token, index) => readToken(read, token, SERIES_FALLBACKS[index])),
  }
}

export function readThemeChartPalette(): ThemeChartPalette {
  if (typeof document === 'undefined' || typeof getComputedStyle === 'undefined') {
    return resolveThemeChartPalette(() => '')
  }
  const styles = getComputedStyle(document.documentElement)
  return resolveThemeChartPalette((name) => styles.getPropertyValue(name))
}
