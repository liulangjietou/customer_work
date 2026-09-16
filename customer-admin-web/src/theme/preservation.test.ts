/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { getThemePreset, resolveThemePreset, THEME_PRESETS } from '@/theme/presets'

describe('保留已存在的主题配色', () => {
  it('主题初始化前的 CSS 默认色保留浅色，暗色选择器保持独立', () => {
    const css = readFileSync(new URL('../theme.css', import.meta.url), 'utf8')
    const root = css.split(':root {')[1]!.split('\n}')[0]!
    const dark = css.split('html.dark {')[1]!.split('\n}')[0]!
    expect(root).toContain('--cw-canvas: #f6f8fb;')
    expect(root).toContain('--cw-paper: #ffffff;')
    expect(root).toContain('--theme-primary: #3e63dd;')
    expect(dark).toContain('--cw-canvas: #09111f;')
    expect(dark).toContain('--cw-paper: #101a2b;')
  })
  it('九套主题保持原主色，布局升级不得改变用户已选色系', () => {
    expect(Object.fromEntries(THEME_PRESETS.map(preset => [preset.id, preset.primaryColor]))).toEqual({
      system: '#2563eb', atlas: '#0f827a', ocean: '#3e63dd', violet: '#7347bd',
      ember: '#b45309', dawn: '#b42362', night: '#14b8a6', aurora: '#8b5cf6', graphite: '#94a3b8',
    })
  })
  it('Ocean 保留原工作面、正文及边界配色', () => {
    expect(resolveThemePreset(getThemePreset('ocean'), false).surface).toMatchObject({
      canvas: '#f6f8fb', text: '#1d2637', textRegular: '#465166', textMuted: '#626d80',
      textPlaceholder: '#657187', line: '#e3e7ef',
    })
  })
})
