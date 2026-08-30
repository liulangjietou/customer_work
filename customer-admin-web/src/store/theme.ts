import { defineStore } from 'pinia'
import { syncHljsTheme } from '@/utils/hljsTheme'
import { createSolidColorPalette, ensureColorContrast } from '@/utils/themeContrast'

const COLOR_STORAGE_KEY = 'customer-admin-theme-color'
const MODE_STORAGE_KEY = 'customer-admin-theme-mode'

/** 预设主题色盘。 */
export const PRESET_COLORS = [
  '#3e63dd', // 钴蓝
  '#14b8a6', // 青绿
  '#8b5cf6', // 紫色
  '#f59e0b', // 琥珀
  '#ef4444', // 红色
  '#ec4899', // 粉色
  '#10b981', // 翠绿
  '#6366f1', // 靛蓝
]

export type ThemeMode = 'light' | 'dark' | 'auto'

interface ThemeState {
  primaryColor: string
  mode: ThemeMode
  systemDark: boolean
}

function getInitialColor(): string {
  if (typeof window === 'undefined') return PRESET_COLORS[0]
  return localStorage.getItem(COLOR_STORAGE_KEY) || PRESET_COLORS[0]
}

function getInitialMode(): ThemeMode {
  if (typeof window === 'undefined') return 'light'
  const saved = localStorage.getItem(MODE_STORAGE_KEY)
  return saved === 'dark' || saved === 'auto' ? saved : 'light'
}

function getInitialSystemDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

interface SystemThemeRegistration {
  mediaQuery: MediaQueryList
  listener: (event: MediaQueryListEvent) => void
}

// 监听器按 Pinia store 实例隔离，避免测试、HMR 或微前端重挂载时仍更新已废弃的实例。
const systemThemeRegistrations = new WeakMap<object, SystemThemeRegistration>()

export const useThemeStore = defineStore('theme', {
  state: (): ThemeState => ({
    primaryColor: getInitialColor(),
    mode: getInitialMode(),
    systemDark: getInitialSystemDark(),
  }),
  getters: {
    /** 当前是否处于暗色（mode=auto 时跟随系统）。 */
    isDark(state): boolean {
      if (state.mode === 'auto') {
        return state.systemDark
      }
      return state.mode === 'dark'
    },
  },
  actions: {
    setPrimaryColor(color: string) {
      this.primaryColor = color
      if (typeof window !== 'undefined') {
        localStorage.setItem(COLOR_STORAGE_KEY, color)
      }
      this.apply()
    },
    setMode(mode: ThemeMode) {
      this.mode = mode
      if (typeof window !== 'undefined') {
        localStorage.setItem(MODE_STORAGE_KEY, mode)
      }
      this.apply()
    },
    /** 明/暗一键切换；auto 状态下切换即落到显式模式。 */
    toggleDark() {
      this.setMode(this.isDark ? 'light' : 'dark')
    },
    /**
     * 把当前主题写入 CSS 变量与 html.dark class，供全站消费。
     *
     * 色阶方向按明暗反转：Element Plus 的 primary-light-N 语义是"越大越接近页面背景"，
     * 亮色下向白色混合、暗色下必须向黑色混合，否则暗色里 hover/禁用态会发白刺眼；
     * dark-2 同理反向。
     */
    apply() {
      if (typeof document === 'undefined') return
      const systemTheme = typeof window !== 'undefined'
        ? window.matchMedia('(prefers-color-scheme: dark)')
        : undefined
      if (systemTheme) {
        this.systemDark = systemTheme.matches
      }
      const dark = this.isDark
      const root = document.documentElement
      root.classList.toggle('dark', dark)

      // 品牌色可很明亮，但链接、Tabs 与焦点环是工作面上的前景色，必须先校准到 AA。
      const surfaceColor = dark ? '#101a2b' : '#ffffff'
      const solidPalette = createSolidColorPalette(this.primaryColor)
      const accessiblePrimary = ensureColorContrast(solidPalette.base, surfaceColor)

      const towardBg = (hex: string, percent: number) => (dark ? this.darken(hex, percent) : this.lighten(hex, percent))
      const awayFromBg = (hex: string, percent: number) => (dark ? this.lighten(hex, percent) : this.darken(hex, percent))

      root.style.setProperty('--theme-primary-source', solidPalette.base)
      root.style.setProperty('--theme-primary', accessiblePrimary)
      root.style.setProperty('--theme-primary-solid', solidPalette.base)
      root.style.setProperty('--theme-primary-solid-hover', solidPalette.hover)
      root.style.setProperty('--theme-primary-solid-active', solidPalette.active)
      root.style.setProperty('--cw-on-primary', solidPalette.onColor)
      root.style.setProperty('--cw-focus-ring', accessiblePrimary)
      root.style.setProperty('--theme-primary-light', towardBg(accessiblePrimary, 30))
      root.style.setProperty('--theme-primary-lighter', towardBg(accessiblePrimary, 45))
      root.style.setProperty('--theme-page-bg', towardBg(solidPalette.base, dark ? 88 : 92))
      // 同步 Element Plus 主色变量，让按钮、tabs、link 等组件自动跟随主题
      root.style.setProperty('--el-color-primary', accessiblePrimary)
      root.style.setProperty('--el-color-primary-light-3', towardBg(accessiblePrimary, 10))
      root.style.setProperty('--el-color-primary-light-5', towardBg(accessiblePrimary, 25))
      root.style.setProperty('--el-color-primary-light-7', towardBg(accessiblePrimary, 40))
      root.style.setProperty('--el-color-primary-light-8', towardBg(accessiblePrimary, 50))
      root.style.setProperty('--el-color-primary-light-9', towardBg(accessiblePrimary, 58))
      root.style.setProperty('--el-color-primary-dark-2', awayFromBg(accessiblePrimary, 10))

      syncHljsTheme(dark)

      if (systemTheme && !systemThemeRegistrations.has(this)) {
        const listener = (event: MediaQueryListEvent) => {
          this.systemDark = event.matches
          if (this.mode === 'auto') {
            this.apply()
          }
        }
        systemTheme.addEventListener('change', listener)
        systemThemeRegistrations.set(this, { mediaQuery: systemTheme, listener })
      }
    },
    /** 释放当前挂载实例的系统主题监听器。 */
    disposeSystemThemeListener() {
      const registration = systemThemeRegistrations.get(this)
      if (!registration) return
      registration.mediaQuery.removeEventListener('change', registration.listener)
      systemThemeRegistrations.delete(this)
    },
    /**
     * 简易颜色提亮：在 16 进制色上按百分比混入白色。
     */
    lighten(hex: string, percent: number): string {
      const num = parseInt(hex.replace('#', ''), 16)
      const r = Math.min(255, Math.round((num >> 16) + (255 - (num >> 16)) * (percent / 100)))
      const g = Math.min(255, Math.round(((num >> 8) & 0x00ff) + (255 - ((num >> 8) & 0x00ff)) * (percent / 100)))
      const b = Math.min(255, Math.round((num & 0x0000ff) + (255 - (num & 0x0000ff)) * (percent / 100)))
      return `rgb(${r}, ${g}, ${b})`
    },
    /**
     * 简易颜色加深：在 16 进制色上按百分比混入黑色。
     */
    darken(hex: string, percent: number): string {
      const num = parseInt(hex.replace('#', ''), 16)
      const r = Math.max(0, Math.round((num >> 16) * (1 - percent / 100)))
      const g = Math.max(0, Math.round(((num >> 8) & 0x00ff) * (1 - percent / 100)))
      const b = Math.max(0, Math.round((num & 0x0000ff) * (1 - percent / 100)))
      return `rgb(${r}, ${g}, ${b})`
    },
  },
})
