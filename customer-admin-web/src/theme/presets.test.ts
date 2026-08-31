import { describe, expect, it } from 'vitest'
import {
  createCustomThemePreset,
  DEFAULT_THEME_PRESET,
  getThemePreset,
  resolveThemePreset,
  SYSTEM_THEME_PRESET,
  THEME_PRESETS,
  VISUAL_THEME_PRESETS,
} from './presets'
import { normalizeHexColor } from '@/utils/themeContrast'

describe('theme preset catalog', () => {
  it('提供 System 与八个稳定视觉主题', () => {
    expect(THEME_PRESETS.map((preset) => preset.id)).toEqual([
      'system',
      'atlas',
      'ocean',
      'violet',
      'ember',
      'dawn',
      'night',
      'aurora',
      'graphite',
    ])
    expect(VISUAL_THEME_PRESETS).toHaveLength(8)
    expect(DEFAULT_THEME_PRESET.id).toBe('ocean')
  })

  it('ID、中英文名称、主色及颜色模式组合均不重复', () => {
    const uniqueCount = (values: string[]) => new Set(values).size

    expect(uniqueCount(THEME_PRESETS.map((preset) => preset.id))).toBe(THEME_PRESETS.length)
    expect(uniqueCount(THEME_PRESETS.map((preset) => preset.name))).toBe(THEME_PRESETS.length)
    expect(uniqueCount(THEME_PRESETS.map((preset) => preset.zhName))).toBe(THEME_PRESETS.length)
    expect(uniqueCount(THEME_PRESETS.map((preset) => preset.primaryColor))).toBe(THEME_PRESETS.length)
    expect(uniqueCount(THEME_PRESETS.map((preset) => `${preset.primaryColor}/${preset.mode}`))).toBe(THEME_PRESETS.length)

    for (const preset of THEME_PRESETS) {
      expect(preset.label).toBe(`${preset.name} ${preset.zhName}`)
      expect(preset.description.trim()).not.toBe('')
      expect(preset.preview.primary).toBe(preset.primaryColor)
    }
  })

  it('每个固定主题有完整的真实 surface，System 同时提供明暗两套', () => {
    for (const preset of VISUAL_THEME_PRESETS) {
      expect(Object.keys(preset.surfaces)).toEqual([preset.mode])
      const resolved = resolveThemePreset(preset, preset.mode === 'dark')
      expect(resolved.appearance).toBe(preset.mode)
      expect(preset.preview).toMatchObject({
        canvas: resolved.surface.canvas,
        paper: resolved.surface.paper,
      })
      for (const color of Object.values(resolved.surface)) {
        expect(normalizeHexColor(color), `${preset.id}: ${color}`).toBe(color)
      }
    }

    expect(Object.keys(SYSTEM_THEME_PRESET.surfaces).sort()).toEqual(['dark', 'light'])
    const light = resolveThemePreset(SYSTEM_THEME_PRESET, false)
    const dark = resolveThemePreset(SYSTEM_THEME_PRESET, true)
    expect(light.appearance).toBe('light')
    expect(dark.appearance).toBe('dark')
    expect(light.surface).not.toEqual(dark.surface)
  })

  it('旧自定义组合保留原颜色和模式，但不进入可选目录', () => {
    const custom = createCustomThemePreset('#abcdef', 'auto', true)

    expect(custom).toMatchObject({
      id: 'custom',
      label: 'Custom 自定义',
      primaryColor: '#abcdef',
      mode: 'auto',
    })
    expect(THEME_PRESETS.some((preset) => preset.id === custom.id)).toBe(false)
    expect(Object.keys(custom.surfaces).sort()).toEqual(['dark', 'light'])
  })

  it('未知 preset id 快速失败', () => {
    expect(() => getThemePreset('custom' as never)).toThrow('Unknown theme preset: custom')
  })
})
