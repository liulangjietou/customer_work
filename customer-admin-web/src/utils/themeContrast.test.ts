import { describe, expect, it } from 'vitest'
import { THEME_PRESETS } from '@/theme/presets'
import {
  chooseButtonTextColor,
  colorContrastRatio,
  createSolidColorPalette,
  DARK_BUTTON_TEXT,
  ensureColorContrast,
  LIGHT_BUTTON_TEXT,
  mixHexColors,
  normalizeHexColor,
  SAFE_THEME_COLOR,
} from './themeContrast'

describe('theme color contrast', () => {
  it('所有主题普通文字在真实 paper 与 canvas 上均满足 WCAG AA', () => {
    for (const preset of THEME_PRESETS) {
      for (const [appearance, surface] of Object.entries(preset.surfaces)) {
        if (!surface) continue
        for (const foreground of [surface.text, surface.textRegular, surface.textMuted, surface.textPlaceholder]) {
          expect.soft(
            colorContrastRatio(foreground, surface.paper),
            `${preset.id}/${appearance}: ${foreground} on paper ${surface.paper}`,
          ).toBeGreaterThanOrEqual(4.5)
          expect.soft(
            colorContrastRatio(foreground, surface.canvas),
            `${preset.id}/${appearance}: ${foreground} on canvas ${surface.canvas}`,
          ).toBeGreaterThanOrEqual(4.5)
        }
      }
    }
  })

  it('所有主题焦点与文字主色都基于真实 paper 校准到 AA', () => {
    for (const preset of THEME_PRESETS) {
      for (const [appearance, surface] of Object.entries(preset.surfaces)) {
        if (!surface) continue
        const foreground = ensureColorContrast(preset.primaryColor, surface.paper)
        expect(
          colorContrastRatio(foreground, surface.paper),
          `${preset.id}/${appearance}: ${foreground} on ${surface.paper}`,
        ).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('所有主题实心按钮默认、悬停与按下状态均满足 AA', () => {
    for (const preset of THEME_PRESETS) {
      const palette = createSolidColorPalette(preset.primaryColor)
      for (const background of [palette.base, palette.hover, palette.active]) {
        expect(
          colorContrastRatio(background, palette.onColor),
          `${preset.id}: ${palette.onColor} on ${background}`,
        ).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('最终动作的琥珀色阶都能选择满足 AA 的文字色', () => {
    for (const background of ['#d99217', '#e7a52c', '#bc7c0f']) {
      const foreground = chooseButtonTextColor(background)
      expect(colorContrastRatio(background, foreground), background).toBeGreaterThanOrEqual(4.5)
    }
  })
})

describe('theme color utilities', () => {
  it('亮背景选择黑字，深背景选择白字', () => {
    expect(chooseButtonTextColor('#f59e0b')).toBe(DARK_BUTTON_TEXT)
    expect(chooseButtonTextColor('#111827')).toBe(LIGHT_BUTTON_TEXT)
  })

  it('规范化合法颜色，并拒绝非六位十六进制值', () => {
    expect(normalizeHexColor('#ABCDEF')).toBe('#abcdef')
    expect(normalizeHexColor('#abc')).toBeNull()
    expect(normalizeHexColor('rgb(1, 2, 3)')).toBeNull()
    expect(normalizeHexColor(null)).toBeNull()
  })

  it('按比例混色并钳制越界比例', () => {
    expect(mixHexColors('#000000', '#ffffff', 0.5)).toBe('#808080')
    expect(mixHexColors('#123456', '#ffffff', -1)).toBe('#123456')
    expect(mixHexColors('#123456', '#ffffff', 2)).toBe('#ffffff')
  })

  it('非法颜色统一回退为安全主题色或白字', () => {
    expect(chooseButtonTextColor('var(--theme-primary)')).toBe(LIGHT_BUTTON_TEXT)
    expect(colorContrastRatio('#ffffff', 'invalid')).toBeNull()
    expect(ensureColorContrast('invalid', '#ffffff')).toBe(SAFE_THEME_COLOR)
    expect(mixHexColors('invalid', '#ffffff', 0.5)).toBe(SAFE_THEME_COLOR)
    expect(createSolidColorPalette('invalid')).toMatchObject({
      base: SAFE_THEME_COLOR,
      onColor: LIGHT_BUTTON_TEXT,
    })
  })
})
