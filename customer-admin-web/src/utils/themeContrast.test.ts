import { describe, expect, it } from 'vitest'
import { PRESET_COLORS } from '@/store/theme'
import {
  chooseButtonTextColor,
  colorContrastRatio,
  DARK_BUTTON_TEXT,
  LIGHT_BUTTON_TEXT,
} from './themeContrast'

describe('chooseButtonTextColor', () => {
  it('全部预设主题色都满足普通文字 WCAG AA 对比度', () => {
    for (const background of PRESET_COLORS) {
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
