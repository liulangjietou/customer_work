import { describe, expect, it } from 'vitest'
import { PRESET_COLORS } from '@/store/theme'
import {
  chooseButtonTextColor,
  colorContrastRatio,
  createSolidColorPalette,
  DARK_BUTTON_TEXT,
  ensureColorContrast,
  LIGHT_BUTTON_TEXT,
} from './themeContrast'

describe('chooseButtonTextColor', () => {
  it('全部预设主题色都满足普通文字 WCAG AA 对比度', () => {
    for (const background of PRESET_COLORS) {
      const foreground = chooseButtonTextColor(background)
      expect(colorContrastRatio(background, foreground), background).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('最终动作的琥珀色阶都能选择满足 AA 的文字色', () => {
    for (const background of ['#d99217', '#e7a52c', '#bc7c0f']) {
      const foreground = chooseButtonTextColor(background)
      expect(colorContrastRatio(background, foreground), background).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('亮背景选择黑字，深背景选择白字', () => {
    expect(chooseButtonTextColor('#f59e0b')).toBe(DARK_BUTTON_TEXT)
    expect(chooseButtonTextColor('#111827')).toBe(LIGHT_BUTTON_TEXT)
  })

  it('非法颜色安全回退为白字', () => {
    expect(chooseButtonTextColor('var(--theme-primary)')).toBe(LIGHT_BUTTON_TEXT)
    expect(colorContrastRatio('#ffffff', 'invalid')).toBeNull()
  })
})

describe('ensureColorContrast', () => {
  it('全部预设色在亮暗工作面上都能作为普通文字与焦点环', () => {
    for (const source of PRESET_COLORS) {
      const lightForeground = ensureColorContrast(source, '#ffffff')
      const darkForeground = ensureColorContrast(source, '#101a2b')
      expect(colorContrastRatio(lightForeground, '#ffffff'), `${source} on light`).toBeGreaterThanOrEqual(4.5)
      expect(colorContrastRatio(darkForeground, '#101a2b'), `${source} on dark`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('已满足阈值时保留原色，非法持久化值回退为钴蓝', () => {
    expect(ensureColorContrast('#3e63dd', '#ffffff')).toBe('#3e63dd')
    expect(ensureColorContrast('invalid', '#ffffff')).toBe('#3e63dd')
  })
})

describe('createSolidColorPalette', () => {
  it('全部预设色的默认、悬停与按下状态都满足实心按钮 AA 对比度', () => {
    for (const source of PRESET_COLORS) {
      const palette = createSolidColorPalette(source)
      for (const background of [palette.base, palette.hover, palette.active]) {
        expect(colorContrastRatio(background, palette.onColor), `${source} -> ${background}`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('非法持久化颜色回退为完整安全色盘', () => {
    expect(createSolidColorPalette('invalid')).toMatchObject({ base: '#3e63dd', onColor: LIGHT_BUTTON_TEXT })
  })
})
