import { describe, expect, it } from 'vitest'
import {
  COMPACT_LAYOUT_MAX_WIDTH,
  DEFAULT_ARTIFACTS_PANEL_WIDTH,
  MAX_ARTIFACTS_PANEL_WIDTH,
  MIN_ARTIFACTS_PANEL_WIDTH,
  STACKED_LAYOUT_MAX_WIDTH,
  clampPreferredArtifactsPanelWidth,
  parseStoredArtifactsPanelWidth,
  resolveVibeCodingLayoutMode,
  resolveVibeCodingPanelResize,
} from './vibeCodingPanelResize'

describe('resolveVibeCodingLayoutMode', () => {
  it('按 700 与 1040 两个边界选择布局', () => {
    expect(resolveVibeCodingLayoutMode(STACKED_LAYOUT_MAX_WIDTH)).toBe('stacked')
    expect(resolveVibeCodingLayoutMode(STACKED_LAYOUT_MAX_WIDTH + 1)).toBe('compact')
    expect(resolveVibeCodingLayoutMode(COMPACT_LAYOUT_MAX_WIDTH)).toBe('compact')
    expect(resolveVibeCodingLayoutMode(COMPACT_LAYOUT_MAX_WIDTH + 1)).toBe('wide')
  })

  it.each([Number.NaN, Number.POSITIVE_INFINITY, -1])('非法容器宽度 %s 安全回落到 stacked', (width) => {
    expect(resolveVibeCodingLayoutMode(width)).toBe('stacked')
  })
})

describe('resolveVibeCodingPanelResize', () => {
  it('wide 展开历史时为对话与历史保留横向空间', () => {
    expect(resolveVibeCodingPanelResize({
      containerWidth: 1200,
      preferredWidth: 500,
      historyCollapsed: false,
    })).toEqual({
      mode: 'wide',
      resizeEnabled: true,
      minWidth: 220,
      maxWidth: 380,
      effectiveWidth: 380,
    })
  })

  it('wide 收起历史时释放 300px 给产物列', () => {
    expect(resolveVibeCodingPanelResize({
      containerWidth: 1200,
      preferredWidth: 500,
      historyCollapsed: true,
    })).toEqual({
      mode: 'wide',
      resizeEnabled: true,
      minWidth: 220,
      maxWidth: 520,
      effectiveWidth: 500,
    })
  })

  it('compact 的历史位于下方，不占横向空间', () => {
    expect(resolveVibeCodingPanelResize({
      containerWidth: 800,
      preferredWidth: 420,
      historyCollapsed: false,
    })).toEqual({
      mode: 'compact',
      resizeEnabled: true,
      minWidth: 220,
      maxWidth: 320,
      effectiveWidth: 320,
    })
  })

  it('可拖布局刚跨过断点时仍守住产物列 220px 下限', () => {
    const compact = resolveVibeCodingPanelResize({
      containerWidth: 701,
      preferredWidth: 500,
      historyCollapsed: false,
    })
    const wide = resolveVibeCodingPanelResize({
      containerWidth: 1041,
      preferredWidth: 500,
      historyCollapsed: false,
    })

    expect(compact.maxWidth).toBe(221)
    expect(compact.effectiveWidth).toBe(221)
    expect(wide.maxWidth).toBe(221)
    expect(wide.effectiveWidth).toBe(221)
  })

  it('stacked 禁止拖动并保留用户偏好', () => {
    expect(resolveVibeCodingPanelResize({
      containerWidth: 700,
      preferredWidth: 420,
      historyCollapsed: false,
    })).toEqual({
      mode: 'stacked',
      resizeEnabled: false,
      minWidth: 220,
      maxWidth: 520,
      effectiveWidth: 420,
    })
  })

  it('窄屏临时 clamp 不会改变后续放大时使用的 preferred', () => {
    const preferredWidth = 500
    const compact = resolveVibeCodingPanelResize({
      containerWidth: 800,
      preferredWidth,
      historyCollapsed: false,
    })
    const wide = resolveVibeCodingPanelResize({
      containerWidth: 1400,
      preferredWidth,
      historyCollapsed: false,
    })

    expect(compact.effectiveWidth).toBe(320)
    expect(wide.effectiveWidth).toBe(preferredWidth)
  })

  it.each([
    [Number.NaN, DEFAULT_ARTIFACTS_PANEL_WIDTH],
    [Number.POSITIVE_INFINITY, DEFAULT_ARTIFACTS_PANEL_WIDTH],
    [-1, MIN_ARTIFACTS_PANEL_WIDTH],
    [999, MAX_ARTIFACTS_PANEL_WIDTH],
  ])('非法或越界 preferred %s 不产生 NaN', (preferredWidth, expected) => {
    const result = resolveVibeCodingPanelResize({
      containerWidth: 1400,
      preferredWidth,
      historyCollapsed: false,
    })

    expect(result.effectiveWidth).toBe(expected)
    expect(Number.isNaN(result.effectiveWidth)).toBe(false)
    expect(Number.isNaN(result.maxWidth)).toBe(false)
  })

  it.each([Number.NaN, Number.POSITIVE_INFINITY, -1])('非法 container %s 不产生 NaN', (containerWidth) => {
    const result = resolveVibeCodingPanelResize({
      containerWidth,
      preferredWidth: 400,
      historyCollapsed: false,
    })

    expect(result.mode).toBe('stacked')
    expect(result.resizeEnabled).toBe(false)
    expect(Number.isNaN(result.effectiveWidth)).toBe(false)
    expect(Number.isNaN(result.maxWidth)).toBe(false)
  })
})

describe('clampPreferredArtifactsPanelWidth', () => {
  it('只将拖动值限制在 220..520', () => {
    expect(clampPreferredArtifactsPanelWidth(100)).toBe(MIN_ARTIFACTS_PANEL_WIDTH)
    expect(clampPreferredArtifactsPanelWidth(360)).toBe(360)
    expect(clampPreferredArtifactsPanelWidth(800)).toBe(MAX_ARTIFACTS_PANEL_WIDTH)
  })
})

describe('parseStoredArtifactsPanelWidth', () => {
  it.each([null, undefined, '', '   ', 'NaN', 'Infinity', '-Infinity', '219', '521', '260px']) (
    '损坏或越界存储值 %s 回落默认宽度',
    (raw) => {
      expect(parseStoredArtifactsPanelWidth(raw)).toBe(DEFAULT_ARTIFACTS_PANEL_WIDTH)
    },
  )

  it('接受范围端点、普通数值与小数', () => {
    expect(parseStoredArtifactsPanelWidth('220')).toBe(220)
    expect(parseStoredArtifactsPanelWidth(' 360 ')).toBe(360)
    expect(parseStoredArtifactsPanelWidth('420.5')).toBe(420.5)
    expect(parseStoredArtifactsPanelWidth('520')).toBe(520)
  })
})
