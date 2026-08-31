import { defineStore } from 'pinia'
import {
  createCustomThemePreset,
  DEFAULT_THEME_PRESET,
  getThemePreset,
  resolveFallbackSurface,
  resolveThemePreset,
  SYSTEM_THEME_PRESET,
  THEME_PRESETS,
  VISUAL_THEME_PRESETS,
  type ActiveThemePresetId,
  type ThemeMode,
  type ThemePreset,
  type ThemePresetId,
} from '@/theme/presets'
import { syncHljsTheme } from '@/utils/hljsTheme'
import {
  createSolidColorPalette,
  ensureColorContrast,
  mixHexColors,
  normalizeHexColor,
} from '@/utils/themeContrast'

export {
  DEFAULT_THEME_PRESET,
  SYSTEM_THEME_PRESET,
  THEME_PRESETS,
  VISUAL_THEME_PRESETS,
}
export type {
  ActiveThemePresetId,
  ResolvedThemeMode,
  SelectableThemePreset,
  ThemeMode,
  ThemePreset,
  ThemePresetId,
  ThemePresetPreview,
  ThemeSurfacePalette,
} from '@/theme/presets'

const COLOR_STORAGE_KEY = 'customer-admin-theme-color'
const MODE_STORAGE_KEY = 'customer-admin-theme-mode'
const SELECTION_STORAGE_KEY = 'customer-admin-theme-selection'
const THEME_SELECTION_VERSION = 1

interface ThemeState {
  activePresetId: ActiveThemePresetId
  primaryColor: string
  mode: ThemeMode
  systemDark: boolean
}

interface StoredPresetSelection {
  version: typeof THEME_SELECTION_VERSION
  kind: 'preset'
  id: ThemePresetId
}

interface StoredCustomSelection {
  version: typeof THEME_SELECTION_VERSION
  kind: 'custom'
  primaryColor: string
  mode: ThemeMode
}

type StoredThemeSelection = StoredPresetSelection | StoredCustomSelection

type StoredThemeSelectionParseResult =
  | { status: 'absent' }
  | { status: 'valid'; selection: StoredThemeSelection }
  | { status: 'invalid' }
  | { status: 'unsupported-version' }

interface StorageReadResult {
  ok: boolean
  value: string | null
}

function isThemeMode(value: unknown): value is ThemeMode {
  return value === 'light' || value === 'dark' || value === 'auto'
}

function browserStorage(): Storage | undefined {
  if (typeof window === 'undefined') return undefined
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage
  } catch {
    return undefined
  }
}

/** Web Storage 是外部能力，所有读写异常只在这一层降级为内存主题。 */
function readStoredValue(storage: Storage, key: string): StorageReadResult {
  try {
    return { ok: true, value: storage.getItem(key) }
  } catch {
    return { ok: false, value: null }
  }
}

function writeStoredValue(storage: Storage, key: string, value: string) {
  try {
    storage.setItem(key, value)
  } catch {
    // 当前页面仍应用内存状态；持久化与兼容镜像均为 best-effort。
  }
}

function isThemePresetId(value: unknown): value is ThemePresetId {
  return typeof value === 'string' && THEME_PRESETS.some((preset) => preset.id === value)
}

function parseStoredThemeSelection(raw: string | null): StoredThemeSelectionParseResult {
  if (raw === null) return { status: 'absent' }
  try {
    const value = JSON.parse(raw) as unknown
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { status: 'invalid' }
    }
    const record = value as Record<string, unknown>
    if (record.version !== THEME_SELECTION_VERSION) {
      return typeof record.version === 'number'
        ? { status: 'unsupported-version' }
        : { status: 'invalid' }
    }
    if (record.kind === 'preset' && isThemePresetId(record.id)) {
      return {
        status: 'valid',
        selection: { version: THEME_SELECTION_VERSION, kind: 'preset', id: record.id },
      }
    }
    const primaryColor = normalizeHexColor(record.primaryColor)
    if (record.kind === 'custom' && primaryColor && isThemeMode(record.mode)) {
      return {
        status: 'valid',
        selection: {
          version: THEME_SELECTION_VERSION,
          kind: 'custom',
          primaryColor,
          mode: record.mode,
        },
      }
    }
  } catch {
    return { status: 'invalid' }
  }
  return { status: 'invalid' }
}

function storedPresetSelection(id: ThemePresetId): StoredPresetSelection {
  return { version: THEME_SELECTION_VERSION, kind: 'preset', id }
}

function storedCustomSelection(primaryColor: string, mode: ThemeMode): StoredCustomSelection {
  return { version: THEME_SELECTION_VERSION, kind: 'custom', primaryColor, mode }
}

function serializeThemeSelection(selection: StoredThemeSelection): string {
  return JSON.stringify(selection)
}

function syncStoredThemeSelection(
  storage: Storage,
  selection: StoredThemeSelection,
  primaryColor: string,
  mode: ThemeMode,
  syncCanonicalSelection = true,
) {
  const serializedSelection = serializeThemeSelection(selection)
  const savedSelection = readStoredValue(storage, SELECTION_STORAGE_KEY)
  if (syncCanonicalSelection && (!savedSelection.ok || savedSelection.value !== serializedSelection)) {
    writeStoredValue(storage, SELECTION_STORAGE_KEY, serializedSelection)
  }
  const savedColor = readStoredValue(storage, COLOR_STORAGE_KEY)
  if (!savedColor.ok || savedColor.value !== primaryColor) {
    writeStoredValue(storage, COLOR_STORAGE_KEY, primaryColor)
  }
  const savedMode = readStoredValue(storage, MODE_STORAGE_KEY)
  if (!savedMode.ok || savedMode.value !== mode) {
    writeStoredValue(storage, MODE_STORAGE_KEY, mode)
  }
}

function initialThemeSelection(): Pick<ThemeState, 'activePresetId' | 'primaryColor' | 'mode'> {
  const storage = browserStorage()
  if (!storage) {
    return {
      activePresetId: DEFAULT_THEME_PRESET.id,
      primaryColor: DEFAULT_THEME_PRESET.primaryColor,
      mode: DEFAULT_THEME_PRESET.mode,
    }
  }

  const savedSelection = readStoredValue(storage, SELECTION_STORAGE_KEY)
  if (!savedSelection.ok) {
    return {
      activePresetId: DEFAULT_THEME_PRESET.id,
      primaryColor: DEFAULT_THEME_PRESET.primaryColor,
      mode: DEFAULT_THEME_PRESET.mode,
    }
  }
  const parsedSelection = parseStoredThemeSelection(savedSelection.value)
  const explicitSelection = parsedSelection.status === 'valid'
    ? parsedSelection.selection
    : undefined
  if (explicitSelection?.kind === 'preset') {
    const preset = getThemePreset(explicitSelection.id)
    syncStoredThemeSelection(storage, explicitSelection, preset.primaryColor, preset.mode)
    return {
      activePresetId: preset.id,
      primaryColor: preset.primaryColor,
      mode: preset.mode,
    }
  }
  if (explicitSelection?.kind === 'custom') {
    syncStoredThemeSelection(
      storage,
      explicitSelection,
      explicitSelection.primaryColor,
      explicitSelection.mode,
    )
    return {
      activePresetId: 'custom',
      primaryColor: explicitSelection.primaryColor,
      mode: explicitSelection.mode,
    }
  }

  const savedColor = readStoredValue(storage, COLOR_STORAGE_KEY)
  const savedMode = readStoredValue(storage, MODE_STORAGE_KEY)
  if (!savedColor.ok || !savedMode.ok) {
    return {
      activePresetId: DEFAULT_THEME_PRESET.id,
      primaryColor: DEFAULT_THEME_PRESET.primaryColor,
      mode: DEFAULT_THEME_PRESET.mode,
    }
  }
  const preserveUnsupportedSelection = parsedSelection.status === 'unsupported-version'
  if (savedColor.value === null && savedMode.value === null) {
    if (parsedSelection.status === 'invalid') {
      const fallbackSelection = storedPresetSelection(DEFAULT_THEME_PRESET.id)
      syncStoredThemeSelection(
        storage,
        fallbackSelection,
        DEFAULT_THEME_PRESET.primaryColor,
        DEFAULT_THEME_PRESET.mode,
      )
    }
    return {
      activePresetId: DEFAULT_THEME_PRESET.id,
      primaryColor: DEFAULT_THEME_PRESET.primaryColor,
      mode: DEFAULT_THEME_PRESET.mode,
    }
  }

  const normalizedColor = savedColor.value === null
    ? DEFAULT_THEME_PRESET.primaryColor
    : normalizeHexColor(savedColor.value)
  const normalizedMode = savedMode.value === null
    ? DEFAULT_THEME_PRESET.mode
    : (isThemeMode(savedMode.value) ? savedMode.value : undefined)

  // 旧版只有颜色与模式两键，无法证明它属于新命名预设；合法组合一律迁移为 Custom。
  if (normalizedColor && normalizedMode) {
    const selection = storedCustomSelection(normalizedColor, normalizedMode)
    syncStoredThemeSelection(
      storage,
      selection,
      normalizedColor,
      normalizedMode,
      !preserveUnsupportedSelection,
    )
    return {
      activePresetId: 'custom',
      primaryColor: normalizedColor,
      mode: normalizedMode,
    }
  }

  const fallbackSelection = storedPresetSelection(DEFAULT_THEME_PRESET.id)
  syncStoredThemeSelection(
    storage,
    fallbackSelection,
    DEFAULT_THEME_PRESET.primaryColor,
    DEFAULT_THEME_PRESET.mode,
    !preserveUnsupportedSelection,
  )
  return {
    activePresetId: DEFAULT_THEME_PRESET.id,
    primaryColor: DEFAULT_THEME_PRESET.primaryColor,
    mode: DEFAULT_THEME_PRESET.mode,
  }
}

function systemThemeQuery(): MediaQueryList | undefined {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined
  return window.matchMedia('(prefers-color-scheme: dark)')
}

function getInitialSystemDark(): boolean {
  return systemThemeQuery()?.matches ?? false
}

function persistThemeSelection(
  selection: StoredThemeSelection,
  primaryColor: string,
  mode: ThemeMode,
) {
  const storage = browserStorage()
  if (!storage) return
  writeStoredValue(storage, SELECTION_STORAGE_KEY, serializeThemeSelection(selection))
  writeStoredValue(storage, COLOR_STORAGE_KEY, primaryColor)
  writeStoredValue(storage, MODE_STORAGE_KEY, mode)
}

interface SystemThemeRegistration {
  mediaQuery: MediaQueryList
  listener: (event: MediaQueryListEvent) => void
}

// 监听器按 Pinia store 实例隔离，避免测试、HMR 或微前端重挂载时仍更新已废弃的实例。
const systemThemeRegistrations = new WeakMap<object, SystemThemeRegistration>()

export const useThemeStore = defineStore('theme', {
  state: (): ThemeState => ({
    ...initialThemeSelection(),
    systemDark: getInitialSystemDark(),
  }),
  getters: {
    /** 当前是否处于暗色（mode=auto 时跟随系统）。 */
    isDark(state): boolean {
      return state.mode === 'auto' ? state.systemDark : state.mode === 'dark'
    },
    /** 预设身份来自显式持久化联合状态，绝不通过展示颜色反推。 */
    activePreset(state): ThemePreset {
      return state.activePresetId === 'custom'
        ? createCustomThemePreset(state.primaryColor, state.mode, state.systemDark)
        : getThemePreset(state.activePresetId)
    },
  },
  actions: {
    /** 一次提交颜色与模式，避免先后 apply 产生一帧不完整主题。 */
    selectPreset(id: ThemePresetId) {
      const preset = getThemePreset(id)
      this.activePresetId = preset.id
      this.primaryColor = preset.primaryColor
      this.mode = preset.mode
      persistThemeSelection(storedPresetSelection(preset.id), preset.primaryColor, preset.mode)
      this.apply()
    },
    /**
     * 把完整主题写入根节点。主色前景基于当前主题真实 paper 校准，而不是假定白色或固定深蓝。
     */
    apply() {
      if (typeof document === 'undefined') return

      const mediaQuery = systemThemeQuery()
      if (mediaQuery) {
        this.systemDark = mediaQuery.matches
      }

      const preset = this.activePresetId === 'custom'
        ? undefined
        : getThemePreset(this.activePresetId)
      const resolved = preset
        ? resolveThemePreset(preset, this.systemDark)
        : resolveFallbackSurface(this.mode, this.systemDark)
      const { appearance, surface } = resolved
      const dark = appearance === 'dark'
      const root = document.documentElement
      root.classList.toggle('dark', dark)
      root.dataset.theme = preset?.id ?? 'custom'
      root.dataset.themeMode = appearance

      const solidPalette = createSolidColorPalette(this.primaryColor)
      const accessiblePrimary = ensureColorContrast(solidPalette.base, surface.paper)
      const primaryDarkTarget = dark ? '#ffffff' : '#000000'

      const variables: Record<string, string> = {
        '--theme-primary-source': solidPalette.base,
        '--theme-primary': accessiblePrimary,
        '--theme-primary-solid': solidPalette.base,
        '--theme-primary-solid-hover': solidPalette.hover,
        '--theme-primary-solid-active': solidPalette.active,
        '--theme-primary-light': mixHexColors(accessiblePrimary, surface.paper, 0.3),
        '--theme-primary-lighter': mixHexColors(accessiblePrimary, surface.paper, 0.45),
        '--theme-page-bg': surface.canvas,

        '--cw-ink': surface.ink,
        '--cw-ink-elevated': surface.inkElevated,
        '--cw-canvas': surface.canvas,
        '--cw-paper': surface.paper,
        '--cw-text': surface.text,
        '--cw-text-muted': surface.textMuted,
        '--cw-line': surface.line,
        '--cw-line-strong': surface.lineStrong,
        '--cw-focus-ring': accessiblePrimary,
        '--cw-on-primary': solidPalette.onColor,
        '--cw-cobalt': accessiblePrimary,
        '--cw-cobalt-solid': solidPalette.base,
        '--cw-cobalt-solid-hover': solidPalette.hover,
        '--cw-cobalt-solid-active': solidPalette.active,
        '--cw-brand-ink': surface.ink,
        '--cw-brand-ink-hover': surface.inkElevated,
        '--cw-brand-logo-start': surface.brandLogoStart,
        '--cw-brand-logo-end': solidPalette.base,
        '--cw-brand-signal': surface.brandSignal,

        '--el-color-primary': accessiblePrimary,
        '--el-color-primary-light-3': mixHexColors(accessiblePrimary, surface.paper, 0.3),
        '--el-color-primary-light-5': mixHexColors(accessiblePrimary, surface.paper, 0.5),
        '--el-color-primary-light-7': mixHexColors(accessiblePrimary, surface.paper, 0.7),
        '--el-color-primary-light-8': mixHexColors(accessiblePrimary, surface.paper, 0.8),
        '--el-color-primary-light-9': mixHexColors(accessiblePrimary, surface.paper, 0.9),
        '--el-color-primary-dark-2': mixHexColors(accessiblePrimary, primaryDarkTarget, 0.2),
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

      for (const [name, value] of Object.entries(variables)) {
        root.style.setProperty(name, value)
      }

      syncHljsTheme(dark)

      if (mediaQuery && !systemThemeRegistrations.has(this)) {
        const listener = (event: MediaQueryListEvent) => {
          this.systemDark = event.matches
          if (this.mode === 'auto') {
            this.apply()
          }
        }
        mediaQuery.addEventListener('change', listener)
        systemThemeRegistrations.set(this, { mediaQuery, listener })
      }
    },
    /** 释放当前挂载实例的系统主题监听器。 */
    disposeSystemThemeListener() {
      const registration = systemThemeRegistrations.get(this)
      if (!registration) return
      registration.mediaQuery.removeEventListener('change', registration.listener)
      systemThemeRegistrations.delete(this)
    },
  },
})
