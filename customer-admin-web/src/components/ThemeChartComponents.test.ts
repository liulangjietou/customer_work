import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createRenderer,
  defineComponent,
  h,
  nextTick,
  ssrContextKey,
  type App,
  type Component,
  type Ref,
  type SetupContext,
} from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import type { ThemeChartPalette } from '@/utils/themeChartPalette'

const echartsMocks = vi.hoisted(() => ({
  init: vi.fn(),
  use: vi.fn(),
}))

const paletteMocks = vi.hoisted(() => ({
  current: {
    text: '#182230',
    axis: '#bac5d6',
    grid: '#dfe5ed',
    tooltipBackground: '#ffffff',
    tooltipBorder: '#dfe5ed',
    series: ['#2864b7', '#16856a', '#b66b19', '#b94f73', '#7347bd', '#16778b', '#a84f28', '#8793a8'],
  },
  read: vi.fn(),
}))

vi.mock('echarts/core', () => ({
  init: echartsMocks.init,
  use: echartsMocks.use,
}))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))
vi.mock('@/utils/themeChartPalette', () => ({
  readThemeChartPalette: paletteMocks.read,
}))
vi.mock('@/utils/hljsTheme', () => ({ syncHljsTheme: vi.fn() }))

import AgentCallTrendChart from './AgentCallTrendChart.vue'
import ContentGuardTrendChart from './ContentGuardTrendChart.vue'
import EvalTrendChart from './EvalTrendChart.vue'
import { useThemeStore } from '@/store/theme'

interface HostNode {
  type: string
  props: Record<string, unknown>
  children: HostNode[]
  parent: HostNode | null
  text?: string
}

function createHostNode(type: string, text?: string): HostNode {
  return { type, props: {}, children: [], parent: null, text }
}

function insertNode(child: HostNode, parent: HostNode, anchor: HostNode | null = null) {
  child.parent = parent
  const index = anchor ? parent.children.indexOf(anchor) : -1
  if (index >= 0) parent.children.splice(index, 0, child)
  else parent.children.push(child)
}

const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previous, value) {
    element.props[key] = value
  },
  insert: insertNode,
  remove(child) {
    const parent = child.parent
    if (!parent) return
    const index = parent.children.indexOf(child)
    if (index >= 0) parent.children.splice(index, 1)
    child.parent = null
  },
  createElement(type) {
    return createHostNode(type)
  },
  createText(text) {
    return createHostNode('#text', text)
  },
  createComment(text) {
    return createHostNode('#comment', text)
  },
  setText(node, text) {
    node.text = text
  },
  setElementText(node, text) {
    node.children = []
    node.text = text
  },
  parentNode(node) {
    return node.parent
  },
  nextSibling(node) {
    const parent = node.parent
    if (!parent) return null
    return parent.children[parent.children.indexOf(node) + 1] ?? null
  },
  querySelector() {
    return null
  },
  setScopeId(element, id) {
    element.props[id] = ''
  },
  insertStaticContent(content, parent, anchor) {
    const node = createHostNode('#static', content)
    insertNode(node, parent, anchor)
    return [node, node]
  },
})

const OCEAN_PALETTE: ThemeChartPalette = {
  text: '#182230',
  axis: '#bac5d6',
  grid: '#dfe5ed',
  tooltipBackground: '#ffffff',
  tooltipBorder: '#dfe5ed',
  series: ['#2864b7', '#16856a', '#b66b19', '#b94f73', '#7347bd', '#16778b', '#a84f28', '#8793a8'],
}

const VIOLET_PALETTE: ThemeChartPalette = {
  text: '#2e2439',
  axis: '#c8bcd6',
  grid: '#e7deed',
  tooltipBackground: '#fffaff',
  tooltipBorder: '#d8cae3',
  series: ['#7347bd', '#2864b7', '#16856a', '#b66b19', '#b94f73', '#16778b', '#a84f28', '#8793a8'],
}

const NIGHT_PALETTE: ThemeChartPalette = {
  text: '#e6edf7',
  axis: '#55647a',
  grid: '#2c394d',
  tooltipBackground: '#111c2d',
  tooltipBorder: '#43526a',
  series: ['#32b5aa', '#65a2ff', '#ffba63', '#e68caf', '#a994ff', '#64c4d4', '#ef8f68', '#9da9bc'],
}

const SYSTEM_LIGHT_PALETTE: ThemeChartPalette = {
  ...OCEAN_PALETTE,
  series: [...OCEAN_PALETTE.series],
}

const SYSTEM_DARK_PALETTE: ThemeChartPalette = {
  ...NIGHT_PALETTE,
  tooltipBackground: '#0e1726',
  series: [...NIGHT_PALETTE.series],
}

interface ChartStub {
  setOption: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
}

interface AxisOption {
  axisLine: { lineStyle: { color: string } }
  axisLabel: { color: string }
  splitLine?: { lineStyle?: { color?: string } }
}

interface SeriesOption {
  symbol?: string
  lineStyle?: { type: string; width: number }
  itemStyle?: { color: string }
  barMaxWidth?: number
}

interface CapturedOption {
  color: string[]
  textStyle: { color: string }
  tooltip: {
    backgroundColor: string
    borderColor: string
    textStyle: { color: string }
  }
  xAxis: AxisOption
  yAxis: AxisOption | AxisOption[]
  series: SeriesOption[]
}

interface MediaHarness {
  emit: (dark: boolean) => void
  listeners: Array<(event: MediaQueryListEvent) => void>
}

function installBrowser(): MediaHarness {
  let systemDark = false
  const listeners: Array<(event: MediaQueryListEvent) => void> = []
  const mediaQuery = {
    get matches() {
      return systemDark
    },
    addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
      listeners.push(listener)
    }),
    removeEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
      const index = listeners.indexOf(listener)
      if (index >= 0) listeners.splice(index, 1)
    }),
  } as unknown as MediaQueryList
  const storage = new Map<string, string>()

  vi.stubGlobal('localStorage', {
    getItem: vi.fn((key: string) => storage.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => storage.set(key, value)),
  })
  vi.stubGlobal('window', {
    matchMedia: vi.fn(() => mediaQuery),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })
  vi.stubGlobal('document', {
    documentElement: {
      classList: { toggle: vi.fn() },
      dataset: {},
      style: { setProperty: vi.fn() },
    },
  })

  return {
    listeners,
    emit(dark: boolean) {
      systemDark = dark
      for (const listener of [...listeners]) {
        listener({ matches: dark } as MediaQueryListEvent)
      }
    },
  }
}

function createChartStub(): ChartStub {
  return {
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  }
}

function latestOption(chart: ChartStub): CapturedOption {
  const call = chart.setOption.mock.calls.at(-1)
  if (!call) throw new Error('Expected chart.setOption to have been called')
  expect(call[1]).toBe(true)
  return call[0] as CapturedOption
}

function expectPalette(option: CapturedOption, palette: ThemeChartPalette) {
  expect(option.color).toEqual(palette.series)
  expect(option.textStyle.color).toBe(palette.text)
  expect(option.tooltip).toMatchObject({
    backgroundColor: palette.tooltipBackground,
    borderColor: palette.tooltipBorder,
    textStyle: { color: palette.text },
  })
  expect(option.xAxis.axisLine.lineStyle.color).toBe(palette.axis)
  expect(option.xAxis.axisLabel.color).toBe(palette.text)

  const axes = Array.isArray(option.yAxis) ? option.yAxis : [option.yAxis]
  expect(axes.every((axis) => axis.axisLine.lineStyle.color === palette.axis)).toBe(true)
  expect(axes.every((axis) => axis.axisLabel.color === palette.text)).toBe(true)
  expect(axes[0]?.splitLine?.lineStyle?.color).toBe(palette.grid)
}

type ChartKind = 'agent' | 'contentGuard' | 'eval'

function expectSeriesEncoding(option: CapturedOption, kind: ChartKind, palette: ThemeChartPalette) {
  if (kind === 'agent') {
    expect(option.series.map((series) => series.symbol)).toEqual([
      'circle', 'rect', 'triangle', 'diamond', 'roundRect', 'pin', 'arrow',
    ])
    expect(option.series.map((series) => series.lineStyle?.type)).toEqual([
      'solid', 'solid', 'dashed', 'dotted', 'dashed', 'dotted', 'solid',
    ])
    expect(option.series[0]?.lineStyle?.width).toBe(2.5)
    return
  }

  if (kind === 'eval') {
    expect(option.series.map((series) => series.symbol)).toEqual(['circle', 'diamond'])
    expect(option.series.map((series) => series.lineStyle?.type)).toEqual(['solid', 'dashed'])
    expect(option.series.map((series) => series.lineStyle?.width)).toEqual([2.5, 2.5])
    return
  }

  expect(option.series[0]).toMatchObject({
    barMaxWidth: 32,
    itemStyle: { color: palette.series[0] },
  })
}

interface ChartCase {
  name: string
  kind: ChartKind
  component: Component
  props: Record<string, unknown>
}

interface SetupBackedComponent {
  setup?: (
    props: Record<string, unknown>,
    context: SetupContext,
  ) => Record<string, unknown> | (() => unknown) | undefined
}

/**
 * Vitest 的 Node 池会把 SFC 编译成 SSR 组件（只有 ssrRender）。这里复用真实 setup，
 * 只提供一个最小客户端 render 来绑定 chartEl，从而让 onMounted/watch 走真实组件生命周期。
 */
function createSetupHarness(component: Component, props: Record<string, unknown>): Component {
  const setup = (component as SetupBackedComponent).setup
  if (!setup) throw new Error('Expected chart component to expose setup')

  return defineComponent({
    setup(_harnessProps, context) {
      const bindings = setup(props, context)
      if (!bindings || typeof bindings === 'function') {
        throw new Error('Expected chart setup bindings')
      }
      const chartEl = bindings.chartEl as Ref<HostNode | undefined>
      return () => h('div', { ref: chartEl })
    },
  })
}

const CHART_CASES: ChartCase[] = [
  {
    name: 'AgentCallTrendChart',
    kind: 'agent',
    component: AgentCallTrendChart,
    props: {
      granularity: 'day',
      points: [{
        bucket: '2026-08-31',
        count: 12,
        avgDurationMs: 230,
        avgModelMs: 150,
        avgToolMs: 30,
        avgMcpMs: 20,
        avgSkillMs: 10,
        totalTokens: 4096,
      }],
    },
  },
  {
    name: 'ContentGuardTrendChart',
    kind: 'contentGuard',
    component: ContentGuardTrendChart,
    props: {
      granularity: 'day',
      points: [{ label: '2026-08-31', total: 7 }],
    },
  },
  {
    name: 'EvalTrendChart',
    kind: 'eval',
    component: EvalTrendChart,
    props: {
      primaryLabel: '准确率',
      secondaryLabel: '快车道覆盖率',
      runs: [{
        runId: 'run-1',
        evalType: 'INTENT',
        total: 10,
        passed: 9,
        primaryMetric: 0.9,
        secondaryMetric: 0.7,
        failedCaseIds: ['case-10'],
        failures: [],
        metrics: {},
        trigger: 'MANUAL',
        datasetSize: 10,
        remark: null,
        createdAtMs: Date.UTC(2026, 7, 31, 8, 30),
      }],
    },
  },
]

describe.each(CHART_CASES)('$name 主题重绘', ({ component, kind, props }) => {
  let app: App<HostNode> | undefined

  afterEach(() => {
    app?.unmount()
    app = undefined
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('初始绘制并在 light→light、light→dark、System 媒体变化时读取最新色盘', async () => {
    const browser = installBrowser()
    const chart = createChartStub()
    echartsMocks.init.mockReturnValue(chart)
    paletteMocks.current = OCEAN_PALETTE
    paletteMocks.read.mockImplementation(() => paletteMocks.current)

    const pinia = createPinia()
    setActivePinia(pinia)
    const theme = useThemeStore(pinia)
    app = renderer.createApp(createSetupHarness(component, props))
    app.use(pinia)
    app.provide(ssrContextKey, { modules: new Set<string>() })
    app.mount(createHostNode('root'))
    await nextTick()

    expect(echartsMocks.init).toHaveBeenCalledOnce()
    expect(chart.setOption).toHaveBeenCalledOnce()
    expectPalette(latestOption(chart), OCEAN_PALETTE)
    expectSeriesEncoding(latestOption(chart), kind, OCEAN_PALETTE)

    let previousCalls = chart.setOption.mock.calls.length
    paletteMocks.current = VIOLET_PALETTE
    theme.selectPreset('violet')
    await nextTick()
    expect(chart.setOption.mock.calls.length).toBeGreaterThan(previousCalls)
    expectPalette(latestOption(chart), VIOLET_PALETTE)
    expectSeriesEncoding(latestOption(chart), kind, VIOLET_PALETTE)

    previousCalls = chart.setOption.mock.calls.length
    paletteMocks.current = NIGHT_PALETTE
    theme.selectPreset('night')
    await nextTick()
    expect(chart.setOption.mock.calls.length).toBeGreaterThan(previousCalls)
    expectPalette(latestOption(chart), NIGHT_PALETTE)
    expectSeriesEncoding(latestOption(chart), kind, NIGHT_PALETTE)

    previousCalls = chart.setOption.mock.calls.length
    paletteMocks.current = SYSTEM_LIGHT_PALETTE
    theme.selectPreset('system')
    await nextTick()
    expect(chart.setOption.mock.calls.length).toBeGreaterThan(previousCalls)
    expectPalette(latestOption(chart), SYSTEM_LIGHT_PALETTE)

    previousCalls = chart.setOption.mock.calls.length
    paletteMocks.current = SYSTEM_DARK_PALETTE
    browser.emit(true)
    await nextTick()
    expect(chart.setOption.mock.calls.length).toBeGreaterThan(previousCalls)
    expectPalette(latestOption(chart), SYSTEM_DARK_PALETTE)
    expectSeriesEncoding(latestOption(chart), kind, SYSTEM_DARK_PALETTE)
    expect(paletteMocks.read).toHaveBeenCalledTimes(chart.setOption.mock.calls.length)
    expect(chart.setOption.mock.calls.every((call) => call[1] === true)).toBe(true)

    theme.disposeSystemThemeListener()
    expect(browser.listeners).toHaveLength(0)
  })
})
