import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('@/utils/hljsTheme', () => ({ syncHljsTheme: vi.fn() }))

import {
  DEFAULT_THEME_PRESET,
  SYSTEM_THEME_PRESET,
  THEME_PRESETS,
  type ThemeSurfacePalette,
  useThemeStore,
} from './theme'
import { resolveFallbackSurface, resolveThemePreset } from '@/theme/presets'
import { colorContrastRatio } from '@/utils/themeContrast'
import { syncHljsTheme } from '@/utils/hljsTheme'

const COLOR_KEY = 'customer-admin-theme-color'
const MODE_KEY = 'customer-admin-theme-mode'
const SELECTION_KEY = 'customer-admin-theme-selection'

interface BrowserHarness {
  classes: Set<string>
  dataset: Record<string, string>
  emitSystemTheme: (dark: boolean) => void
  listeners: Array<(event: MediaQueryListEvent) => void>
  mediaQuery: MediaQueryList
  setItem: ReturnType<typeof vi.fn>
  storage: Map<string, string>
  values: Map<string, string>
}

function installBrowser(
  initialStorage: Record<string, string> = {},
  initialSystemDark = false,
  storageFailure: 'none' | 'read' | 'write' = 'none',
): BrowserHarness {
  let systemDark = initialSystemDark
  const listeners: Array<(event: MediaQueryListEvent) => void> = []
  const storage = new Map(Object.entries(initialStorage))
  const values = new Map<string, string>()
  const classes = new Set<string>()
  const dataset: Record<string, string> = {}
  const setItem = vi.fn((key: string, value: string) => {
    if (storageFailure === 'write') throw new DOMException('storage denied', 'SecurityError')
    storage.set(key, value)
  })
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
  const localStorageStub = {
    getItem: vi.fn((key: string) => {
      if (storageFailure === 'read') throw new DOMException('storage denied', 'SecurityError')
      return storage.get(key) ?? null
    }),
    setItem,
    removeItem: vi.fn((key: string) => storage.delete(key)),
    clear: vi.fn(() => storage.clear()),
    key: vi.fn(),
    get length() {
      return storage.size
    },
  } as unknown as Storage

  vi.stubGlobal('localStorage', localStorageStub)
  vi.stubGlobal('window', { matchMedia: vi.fn(() => mediaQuery) })
  vi.stubGlobal('document', {
    documentElement: {
      classList: {
        toggle: (name: string, enabled: boolean) => (enabled ? classes.add(name) : classes.delete(name)),
      },
      dataset,
      style: { setProperty: (name: string, value: string) => values.set(name, value) },
    },
  })

  return {
    classes,
    dataset,
    listeners,
    mediaQuery,
    setItem,
    storage,
    values,
    emitSystemTheme(dark: boolean) {
      systemDark = dark
      for (const listener of [...listeners]) {
        listener({ matches: dark } as MediaQueryListEvent)
      }
    },
  }
}

function createThemeStore() {
  setActivePinia(createPinia())
  return useThemeStore()
}

function expectSurfaceVariables(values: Map<string, string>, surface: ThemeSurfacePalette) {
  const expectedVariables: Record<string, string> = {
    '--cw-ink': surface.ink,
    '--cw-ink-elevated': surface.inkElevated,
    '--cw-canvas': surface.canvas,
    '--cw-paper': surface.paper,
    '--cw-text': surface.text,
    '--cw-text-muted': surface.textMuted,
    '--cw-line': surface.line,
    '--cw-line-strong': surface.lineStrong,
    '--cw-brand-ink': surface.ink,
    '--cw-brand-ink-hover': surface.inkElevated,
    '--cw-brand-logo-start': surface.brandLogoStart,
    '--cw-brand-signal': surface.brandSignal,
    '--theme-page-bg': surface.canvas,
    '--el-bg-color': surface.paper,
    '--el-bg-color-overlay': surface.paper,
    '--el-bg-color-page': surface.canvas,
    '--el-text-color-primary': surface.text,
    '--el-text-color-regular': surface.textRegular,
    '--el-text-color-secondary': surface.textMuted,
    '--el-text-color-placeholder': surface.textPlaceholder,
    '--el-text-color-disabled': surface.textDisabled,
    '--el-border-color': surface.line,
    '--el-border-color-dark': surface.lineStrong,
    '--el-border-color-darker': surface.lineStrong,
    '--el-border-color-light': surface.borderLight,
    '--el-border-color-lighter': surface.borderLighter,
    '--el-border-color-extra-light': surface.fillExtraLight,
    '--el-fill-color': surface.fill,
    '--el-fill-color-darker': surface.fillDarker,
    '--el-fill-color-dark': surface.fillDark,
    '--el-fill-color-light': surface.fillLight,
    '--el-fill-color-lighter': surface.fillLighter,
    '--el-fill-color-extra-light': surface.fillExtraLight,
    '--el-fill-color-blank': surface.paper,
  }

  for (const [name, expected] of Object.entries(expectedVariables)) {
    expect(values.get(name), name).toBe(expected)
  }
}

describe('theme store', () => {
  afterEach(() => {
    vi.clearAllMocks()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('首次访问默认使用 Ocean，且不产生持久化副作用', () => {
    const browser = installBrowser()
    const theme = createThemeStore()

    expect(theme.primaryColor).toBe(DEFAULT_THEME_PRESET.primaryColor)
    expect(theme.mode).toBe('light')
    expect(theme.activePreset.id).toBe('ocean')
    expect(browser.setItem).not.toHaveBeenCalled()
    expect([...browser.storage.keys()]).toEqual([])
  })

  it('损坏的 v1 selection 在没有兼容两键时自愈为显式 Ocean', () => {
    const browser = installBrowser({ [SELECTION_KEY]: '{broken-json' })
    const theme = createThemeStore()

    expect(theme.activePreset.id).toBe('ocean')
    expect(browser.storage.get(SELECTION_KEY)).toBe(JSON.stringify({
      version: 1,
      kind: 'preset',
      id: 'ocean',
    }))
    expect(browser.storage.get(COLOR_KEY)).toBe(DEFAULT_THEME_PRESET.primaryColor)
    expect(browser.storage.get(MODE_KEY)).toBe(DEFAULT_THEME_PRESET.mode)
  })

  it('未来版本 selection 不被旧客户端覆盖，无兼容镜像时仅以内存 Ocean 降级', () => {
    const futureSelection = JSON.stringify({ version: 2, kind: 'preset', id: 'future-theme' })
    const browser = installBrowser({ [SELECTION_KEY]: futureSelection })
    const theme = createThemeStore()

    expect(theme.activePreset.id).toBe('ocean')
    expect(browser.storage.get(SELECTION_KEY)).toBe(futureSelection)
    expect(browser.setItem).not.toHaveBeenCalled()
  })

  it('Web Storage 读取被禁用时以默认内存主题启动，不阻断 Store 创建', () => {
    installBrowser({}, false, 'read')

    expect(() => createThemeStore()).not.toThrow()
    const theme = createThemeStore()
    expect(theme.activePreset.id).toBe('ocean')
    expect(theme.mode).toBe('light')
  })

  it('selectPreset 一次写入显式身份与兼容两键，并只 apply 一次', () => {
    const browser = installBrowser()
    const theme = createThemeStore()
    const apply = vi.spyOn(theme, 'apply').mockImplementation(() => undefined)

    theme.selectPreset('violet')

    expect(theme.primaryColor).toBe('#7347bd')
    expect(theme.mode).toBe('light')
    expect(theme.activePreset.id).toBe('violet')
    expect(browser.setItem).toHaveBeenCalledTimes(3)
    expect(browser.setItem).toHaveBeenNthCalledWith(
      1,
      SELECTION_KEY,
      JSON.stringify({ version: 1, kind: 'preset', id: 'violet' }),
    )
    expect(browser.setItem).toHaveBeenNthCalledWith(2, COLOR_KEY, '#7347bd')
    expect(browser.setItem).toHaveBeenNthCalledWith(3, MODE_KEY, 'light')
    expect(apply).toHaveBeenCalledTimes(1)
    expect([...browser.storage.keys()].sort()).toEqual([COLOR_KEY, MODE_KEY, SELECTION_KEY].sort())
  })

  it('Web Storage 写入失败时仍完成当前页面的主题切换与 CSS apply', () => {
    const browser = installBrowser({}, false, 'write')
    const theme = createThemeStore()

    expect(() => theme.selectPreset('night')).not.toThrow()
    expect(theme.activePreset.id).toBe('night')
    expect(theme.mode).toBe('dark')
    expect(browser.classes.has('dark')).toBe(true)
    expect(browser.dataset).toEqual({ theme: 'night', themeMode: 'dark' })
    expect(browser.values.get('--cw-paper')).toBe('#0d242c')
    expect(browser.storage.size).toBe(0)
    theme.disposeSystemThemeListener()
  })

  it('按预设真实 paper/canvas/text/fill 写完整变量，并同步 cobalt 兼容别名', () => {
    const browser = installBrowser()
    const theme = createThemeStore()

    theme.selectPreset('ember')

    const { surface } = resolveThemePreset(theme.activePreset, false)
    expect(browser.dataset).toEqual({ theme: 'ember', themeMode: 'light' })
    expect(browser.values.get('--cw-paper')).toBe(surface.paper)
    expect(browser.values.get('--cw-canvas')).toBe(surface.canvas)
    expect(browser.values.get('--cw-text')).toBe(surface.text)
    expect(browser.values.get('--cw-line')).toBe(surface.line)
    expect(browser.values.get('--el-fill-color')).toBe(surface.fill)
    expect(browser.values.get('--el-bg-color-overlay')).toBe(surface.paper)
    expect(browser.values.get('--theme-page-bg')).toBe(surface.canvas)
    expect(browser.values.get('--cw-cobalt')).toBe(browser.values.get('--theme-primary'))
    expect(browser.values.get('--cw-cobalt-solid')).toBe(browser.values.get('--theme-primary-solid'))
    expect(colorContrastRatio(browser.values.get('--theme-primary') ?? '', surface.paper)).toBeGreaterThanOrEqual(4.5)
    expect(syncHljsTheme).toHaveBeenLastCalledWith(false)

    theme.disposeSystemThemeListener()
  })

  it('逐项写入每套固定主题的完整 surface，并在 Night 切回 Atlas 时清除暗色状态', () => {
    const browser = installBrowser()
    const theme = createThemeStore()

    for (const preset of THEME_PRESETS.filter((item) => item.id !== 'system')) {
      theme.selectPreset(preset.id)
      const { appearance, surface } = resolveThemePreset(preset, false)

      expect(browser.dataset).toEqual({ theme: preset.id, themeMode: appearance })
      expect(browser.classes.has('dark')).toBe(appearance === 'dark')
      expectSurfaceVariables(browser.values, surface)
    }

    theme.selectPreset('night')
    expect(browser.classes.has('dark')).toBe(true)
    theme.selectPreset('atlas')
    const atlas = THEME_PRESETS.find((preset) => preset.id === 'atlas')!
    const atlasSurface = resolveThemePreset(atlas, false).surface
    expect(browser.classes.has('dark')).toBe(false)
    expect(browser.dataset).toEqual({ theme: 'atlas', themeMode: 'light' })
    expectSurfaceVariables(browser.values, atlasSurface)

    theme.disposeSystemThemeListener()
  })

  it('保留升级前合法颜色与模式，规范化颜色后识别为 Custom', () => {
    const browser = installBrowser({ [COLOR_KEY]: '#ABCDEF', [MODE_KEY]: 'dark' })
    const theme = createThemeStore()

    expect(theme.primaryColor).toBe('#abcdef')
    expect(theme.mode).toBe('dark')
    expect(theme.activePreset).toMatchObject({
      id: 'custom',
      label: 'Custom 自定义',
      primaryColor: '#abcdef',
      mode: 'dark',
    })
    expect(browser.storage.get(COLOR_KEY)).toBe('#abcdef')
    expect(browser.storage.get(MODE_KEY)).toBe('dark')
    expect(browser.storage.get(SELECTION_KEY)).toBe(JSON.stringify({
      version: 1,
      kind: 'custom',
      primaryColor: '#abcdef',
      mode: 'dark',
    }))
    expect(browser.setItem).toHaveBeenCalledTimes(2)

    theme.apply()
    const { surface } = resolveFallbackSurface('dark', false)
    expect(browser.values.get('--cw-paper')).toBe(surface.paper)
    expect(browser.values.get('--theme-primary-source')).toBe('#abcdef')
    expect(browser.dataset.theme).toBe('custom')
    theme.disposeSystemThemeListener()
  })

  it('旧组合即使与 Night 的展示属性相同，也迁移为 Custom 而不是反推命名预设', () => {
    const browser = installBrowser({ [COLOR_KEY]: '#14b8a6', [MODE_KEY]: 'dark' })
    const theme = createThemeStore()

    expect(theme.activePreset).toMatchObject({
      id: 'custom',
      primaryColor: '#14b8a6',
      mode: 'dark',
    })
    expect(browser.storage.get(SELECTION_KEY)).toBe(JSON.stringify({
      version: 1,
      kind: 'custom',
      primaryColor: '#14b8a6',
      mode: 'dark',
    }))

    theme.apply()
    const fallback = resolveFallbackSurface('dark', false)
    expect(browser.dataset.theme).toBe('custom')
    expect(browser.values.get('--cw-paper')).toBe(fallback.surface.paper)
    expect(browser.values.get('--cw-paper')).not.toBe('#0d242c')
    theme.disposeSystemThemeListener()
  })

  it('显式预设身份优先于过期兼容两键，并把镜像值校正为预设目录', () => {
    const browser = installBrowser({
      [SELECTION_KEY]: JSON.stringify({ version: 1, kind: 'preset', id: 'night' }),
      [COLOR_KEY]: '#3e63dd',
      [MODE_KEY]: 'light',
    })
    const theme = createThemeStore()

    expect(theme.activePreset.id).toBe('night')
    expect(theme.primaryColor).toBe('#14b8a6')
    expect(theme.mode).toBe('dark')
    expect(browser.storage.get(COLOR_KEY)).toBe('#14b8a6')
    expect(browser.storage.get(MODE_KEY)).toBe('dark')
    expect(browser.storage.get(SELECTION_KEY)).toBe(JSON.stringify({
      version: 1,
      kind: 'preset',
      id: 'night',
    }))
  })

  it('非法旧键清洗为 Ocean 安全默认值', () => {
    const browser = installBrowser({ [COLOR_KEY]: 'tomato', [MODE_KEY]: 'sepia' })
    const theme = createThemeStore()

    expect(theme.primaryColor).toBe(DEFAULT_THEME_PRESET.primaryColor)
    expect(theme.mode).toBe(DEFAULT_THEME_PRESET.mode)
    expect(theme.activePreset.id).toBe('ocean')
    expect(browser.storage.get(COLOR_KEY)).toBe(DEFAULT_THEME_PRESET.primaryColor)
    expect(browser.storage.get(MODE_KEY)).toBe(DEFAULT_THEME_PRESET.mode)
    expect(browser.storage.get(SELECTION_KEY)).toBe(JSON.stringify({
      version: 1,
      kind: 'preset',
      id: 'ocean',
    }))
    expect(browser.setItem).toHaveBeenCalledTimes(3)
  })

  it('System 的 auto 模式随系统实时切换两套 surface 与代码主题', () => {
    const browser = installBrowser({}, false)
    const theme = createThemeStore()

    theme.selectPreset('system')
    const light = resolveThemePreset(SYSTEM_THEME_PRESET, false)
    const dark = resolveThemePreset(SYSTEM_THEME_PRESET, true)

    expect(theme.activePreset.id).toBe('system')
    expect(theme.mode).toBe('auto')
    expect(theme.isDark).toBe(false)
    expect(browser.classes.has('dark')).toBe(false)
    expect(browser.values.get('--cw-paper')).toBe(light.surface.paper)
    expect(syncHljsTheme).toHaveBeenLastCalledWith(false)

    browser.emitSystemTheme(true)

    expect(theme.systemDark).toBe(true)
    expect(theme.isDark).toBe(true)
    expect(browser.classes.has('dark')).toBe(true)
    expect(browser.dataset.theme).toBe('system')
    expect(browser.dataset.themeMode).toBe('dark')
    expect(browser.values.get('--cw-paper')).toBe(dark.surface.paper)
    expect(browser.values.get('--cw-canvas')).toBe(dark.surface.canvas)
    expect(colorContrastRatio(browser.values.get('--cw-focus-ring') ?? '', dark.surface.paper)).toBeGreaterThanOrEqual(4.5)
    expect(syncHljsTheme).toHaveBeenLastCalledWith(true)

    theme.disposeSystemThemeListener()
    expect(browser.mediaQuery.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
    expect(browser.listeners).toHaveLength(0)
  })

  it('重建 Pinia 后每个主题实例独立监听并释放系统变化', () => {
    const browser = installBrowser()
    const first = createThemeStore()
    first.apply()
    const second = createThemeStore()
    second.apply()

    expect(browser.listeners).toHaveLength(2)
    browser.emitSystemTheme(true)
    expect(first.systemDark).toBe(true)
    expect(second.systemDark).toBe(true)

    first.disposeSystemThemeListener()
    expect(browser.listeners).toHaveLength(1)
    second.disposeSystemThemeListener()
    expect(browser.listeners).toHaveLength(0)
    expect(browser.mediaQuery.removeEventListener).toHaveBeenCalledTimes(2)
  })

  it('固定主题只记录系统变化，不跟随系统切换自身明暗与 surface', () => {
    const browser = installBrowser({}, false)
    const theme = createThemeStore()
    theme.selectPreset('atlas')
    const atlasPaper = browser.values.get('--cw-paper')
    const syncCallsBeforeSystemChange = vi.mocked(syncHljsTheme).mock.calls.length

    browser.emitSystemTheme(true)

    expect(theme.systemDark).toBe(true)
    expect(theme.isDark).toBe(false)
    expect(browser.classes.has('dark')).toBe(false)
    expect(browser.dataset).toEqual({ theme: 'atlas', themeMode: 'light' })
    expect(browser.values.get('--cw-paper')).toBe(atlasPaper)
    expect(vi.mocked(syncHljsTheme)).toHaveBeenCalledTimes(syncCallsBeforeSystemChange)
    theme.disposeSystemThemeListener()
  })
})
