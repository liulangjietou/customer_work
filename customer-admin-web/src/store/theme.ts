import { defineStore } from 'pinia'

const STORAGE_KEY = 'customer-admin-theme-color'

/** 预设主题色盘。 */
export const PRESET_COLORS = [
  '#409eff', // 默认蓝
  '#14b8a6', // 青绿
  '#8b5cf6', // 紫色
  '#f59e0b', // 琥珀
  '#ef4444', // 红色
  '#ec4899', // 粉色
  '#10b981', // 翠绿
  '#6366f1', // 靛蓝
]

interface ThemeState {
  primaryColor: string
}

function getInitialColor(): string {
  if (typeof window === 'undefined') return PRESET_COLORS[0]
  return localStorage.getItem(STORAGE_KEY) || PRESET_COLORS[0]
}

export const useThemeStore = defineStore('theme', {
  state: (): ThemeState => ({
    primaryColor: getInitialColor(),
  }),
  actions: {
    setPrimaryColor(color: string) {
      this.primaryColor = color
      if (typeof window !== 'undefined') {
        localStorage.setItem(STORAGE_KEY, color)
      }
      this.apply()
    },
    /** 把当前主题色写入 CSS 变量，供全站消费。 */
    apply() {
      if (typeof document === 'undefined') return
      const root = document.documentElement
      root.style.setProperty('--theme-primary', this.primaryColor)
      root.style.setProperty('--theme-primary-light', this.lighten(this.primaryColor, 30))
      root.style.setProperty('--theme-primary-lighter', this.lighten(this.primaryColor, 45))
      root.style.setProperty('--theme-page-bg', this.lighten(this.primaryColor, 92))
      // 同步 Element Plus 主色变量，让按钮、tabs、link 等组件自动跟随主题
      root.style.setProperty('--el-color-primary', this.primaryColor)
      root.style.setProperty('--el-color-primary-light-3', this.lighten(this.primaryColor, 10))
      root.style.setProperty('--el-color-primary-light-5', this.lighten(this.primaryColor, 25))
      root.style.setProperty('--el-color-primary-light-7', this.lighten(this.primaryColor, 40))
      root.style.setProperty('--el-color-primary-light-8', this.lighten(this.primaryColor, 50))
      root.style.setProperty('--el-color-primary-light-9', this.lighten(this.primaryColor, 58))
      root.style.setProperty('--el-color-primary-dark-2', this.darken(this.primaryColor, 10))
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
