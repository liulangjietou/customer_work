import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useThemeStore } from './theme'
import { colorContrastRatio } from '@/utils/themeContrast'

describe('theme store', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('auto 模式随系统主题变化，并立即刷新暗色语义变量', () => {
    let systemDark = false
    let changeListener: ((event: MediaQueryListEvent) => void) | undefined
    const values = new Map<string, string>()
    const classes = new Set<string>()
    const storage = new Map<string, string>()
    const mediaQuery = {
      get matches() {
        return systemDark
      },
      addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
        changeListener = listener
      }),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList
    const root = {
      classList: {
        toggle: (name: string, enabled: boolean) => (enabled ? classes.add(name) : classes.delete(name)),
      },
      style: {
        setProperty: (name: string, value: string) => values.set(name, value),
      },
    }
    const localStorageStub = {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    }

    vi.stubGlobal('document', {
      documentElement: root,
      createElement: () => ({ textContent: '', disabled: false }),
      head: { append: vi.fn() },
    })
    vi.stubGlobal('localStorage', localStorageStub)
    vi.stubGlobal('window', { matchMedia: vi.fn(() => mediaQuery) })
    setActivePinia(createPinia())

    const theme = useThemeStore()
    theme.setMode('auto')

    expect(theme.isDark).toBe(false)
    expect(classes.has('dark')).toBe(false)
    expect(storage.get('customer-admin-theme-mode')).toBe('auto')

    systemDark = true
    changeListener?.({ matches: true } as MediaQueryListEvent)

    expect(theme.systemDark).toBe(true)
    expect(theme.isDark).toBe(true)
    expect(classes.has('dark')).toBe(true)
    expect(values.get('--theme-page-bg')).not.toBe('')
    expect(colorContrastRatio(values.get('--el-color-primary') ?? '', '#101a2b')).toBeGreaterThanOrEqual(4.5)
    expect(values.get('--cw-focus-ring')).toBe(values.get('--el-color-primary'))

    theme.disposeSystemThemeListener()
    expect(mediaQuery.removeEventListener).toHaveBeenCalledWith('change', changeListener)
  })

  it('重建 Pinia 后每个主题实例都监听系统变化', () => {
    let systemDark = false
    const listeners: Array<(event: MediaQueryListEvent) => void> = []
    const mediaQuery = {
      get matches() {
        return systemDark
      },
      addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.push(listener)),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList
    const root = {
      classList: { toggle: vi.fn() },
      style: { setProperty: vi.fn() },
    }
    vi.stubGlobal('document', {
      documentElement: root,
      createElement: () => ({ textContent: '', disabled: false }),
      head: { append: vi.fn() },
    })
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => null), setItem: vi.fn() })
    vi.stubGlobal('window', { matchMedia: vi.fn(() => mediaQuery) })

    setActivePinia(createPinia())
    const first = useThemeStore()
    first.setMode('auto')
    setActivePinia(createPinia())
    const second = useThemeStore()
    second.setMode('auto')

    expect(listeners).toHaveLength(2)
    systemDark = true
    listeners.forEach((listener) => listener({ matches: true } as MediaQueryListEvent))
    expect(first.systemDark).toBe(true)
    expect(second.systemDark).toBe(true)

    first.disposeSystemThemeListener()
    second.disposeSystemThemeListener()
    expect(mediaQuery.removeEventListener).toHaveBeenCalledTimes(2)
  })
})
