/** 主题持久化模式；auto 仅用于随系统主题。 */
export type ThemeMode = 'light' | 'dark' | 'auto'

export type ResolvedThemeMode = Exclude<ThemeMode, 'auto'>

export type ThemePresetId =
  | 'system'
  | 'atlas'
  | 'ocean'
  | 'violet'
  | 'ember'
  | 'dawn'
  | 'night'
  | 'aurora'
  | 'graphite'

export type ActiveThemePresetId = ThemePresetId | 'custom'

/**
 * 一套可直接落到全局 CSS token 的工作面色盘。
 *
 * 主色不放在这里：它既是用户持久化值，也是实心动作色；工作面只负责背景、文字、边界与填充。
 */
export interface ThemeSurfacePalette {
  canvas: string
  paper: string
  ink: string
  inkElevated: string
  text: string
  textRegular: string
  textMuted: string
  textPlaceholder: string
  textDisabled: string
  line: string
  lineStrong: string
  borderLight: string
  borderLighter: string
  fill: string
  fillDarker: string
  fillDark: string
  fillLight: string
  fillLighter: string
  fillExtraLight: string
  brandLogoStart: string
  brandSignal: string
}

export interface ThemePresetPreview {
  primary: string
  canvas: string
  paper: string
}

export interface ThemePreset {
  id: ActiveThemePresetId
  name: string
  zhName: string
  label: string
  description: string
  primaryColor: string
  mode: ThemeMode
  preview: ThemePresetPreview
  /** 固定主题仅含自身模式；System 同时提供 light 与 dark。 */
  surfaces: Readonly<Partial<Record<ResolvedThemeMode, ThemeSurfacePalette>>>
}

export interface SelectableThemePreset extends ThemePreset {
  id: ThemePresetId
}

export interface ResolvedThemePreset {
  appearance: ResolvedThemeMode
  surface: ThemeSurfacePalette
}

const SYSTEM_LIGHT_SURFACE: ThemeSurfacePalette = {
  canvas: '#f4f6fa',
  paper: '#ffffff',
  ink: '#0b1630',
  inkElevated: '#152441',
  text: '#172033',
  textRegular: '#465066',
  textMuted: '#677189',
  textPlaceholder: '#677189',
  textDisabled: '#7b8496',
  line: '#dce1ea',
  lineStrong: '#c9d0dc',
  borderLight: '#e4e8ef',
  borderLighter: '#edf0f5',
  fill: '#eef1f6',
  fillDarker: '#d8dee8',
  fillDark: '#e4e8ef',
  fillLight: '#f3f5f8',
  fillLighter: '#f7f8fa',
  fillExtraLight: '#fafbfc',
  brandLogoStart: '#6681ef',
  brandSignal: '#8ba0ef',
}

const SYSTEM_DARK_SURFACE: ThemeSurfacePalette = {
  canvas: '#09111f',
  paper: '#101a2b',
  ink: '#09111f',
  inkElevated: '#152441',
  text: '#edf2fb',
  textRegular: '#cad3e3',
  textMuted: '#9da9bd',
  textPlaceholder: '#9da9bd',
  textDisabled: '#7f8ca2',
  line: '#28354a',
  lineStrong: '#3b4961',
  borderLight: '#233047',
  borderLighter: '#1d293d',
  fill: '#1a263b',
  fillDarker: '#202d43',
  fillDark: '#172338',
  fillLight: '#172338',
  fillLighter: '#142033',
  fillExtraLight: '#111c2e',
  brandLogoStart: '#8ea6ff',
  brandSignal: '#7188dc',
}

const ATLAS_LIGHT_SURFACE: ThemeSurfacePalette = {
  canvas: '#f1f6f5',
  paper: '#ffffff',
  ink: '#102421',
  inkElevated: '#193a35',
  text: '#18322e',
  textRegular: '#405b56',
  textMuted: '#5f756f',
  textPlaceholder: '#5f756f',
  textDisabled: '#7f918d',
  line: '#d5e2df',
  lineStrong: '#bfd2ce',
  borderLight: '#dfebe8',
  borderLighter: '#e9f1ef',
  fill: '#e9f1ef',
  fillDarker: '#d8e6e3',
  fillDark: '#dfebe8',
  fillLight: '#f0f5f4',
  fillLighter: '#f5f8f7',
  fillExtraLight: '#f9fbfa',
  brandLogoStart: '#42aaa1',
  brandSignal: '#75bdb6',
}

const OCEAN_LIGHT_SURFACE: ThemeSurfacePalette = {
  ...SYSTEM_LIGHT_SURFACE,
  canvas: '#f2f6fc',
  ink: '#0b1733',
  inkElevated: '#14284f',
  text: '#17243d',
  textRegular: '#44536d',
  textMuted: '#62718a',
  textPlaceholder: '#62718a',
  line: '#d8e1ee',
  lineStrong: '#c3cfdf',
  borderLight: '#e1e8f1',
  borderLighter: '#eaf0f6',
  fill: '#eaf0f7',
  fillDarker: '#d6e0ed',
  fillDark: '#e0e8f2',
  fillLight: '#f0f4f9',
  fillLighter: '#f6f8fb',
  fillExtraLight: '#fafbfd',
}

const VIOLET_LIGHT_SURFACE: ThemeSurfacePalette = {
  canvas: '#f7f3fa',
  paper: '#fffefe',
  ink: '#261b33',
  inkElevated: '#3a2850',
  text: '#30233e',
  textRegular: '#574866',
  textMuted: '#71627f',
  textPlaceholder: '#71627f',
  textDisabled: '#91859c',
  line: '#e4dceb',
  lineStrong: '#d2c4dd',
  borderLight: '#ebe4f0',
  borderLighter: '#f1ebf4',
  fill: '#efe8f3',
  fillDarker: '#e1d5e8',
  fillDark: '#e8dfed',
  fillLight: '#f5f0f7',
  fillLighter: '#f9f6fa',
  fillExtraLight: '#fcfafc',
  brandLogoStart: '#9b73d8',
  brandSignal: '#b299d4',
}

const EMBER_LIGHT_SURFACE: ThemeSurfacePalette = {
  canvas: '#fbf5ee',
  paper: '#fffdf9',
  ink: '#2c1c13',
  inkElevated: '#4b2d1c',
  text: '#38261d',
  textRegular: '#665045',
  textMuted: '#776258',
  textPlaceholder: '#806d64',
  textDisabled: '#95857d',
  line: '#eadfd3',
  lineStrong: '#d9c8b8',
  borderLight: '#f0e6dc',
  borderLighter: '#f5ede5',
  fill: '#f4eadf',
  fillDarker: '#e8d9ca',
  fillDark: '#eee1d5',
  fillLight: '#f8f1e9',
  fillLighter: '#fbf7f2',
  fillExtraLight: '#fdfaf7',
  brandLogoStart: '#d7823f',
  brandSignal: '#dea879',
}

const DAWN_LIGHT_SURFACE: ThemeSurfacePalette = {
  canvas: '#fbf3f6',
  paper: '#fffdfd',
  ink: '#321b25',
  inkElevated: '#51283a',
  text: '#3d2730',
  textRegular: '#684b58',
  textMuted: '#7a616c',
  textPlaceholder: '#7a616c',
  textDisabled: '#99858e',
  line: '#eadce2',
  lineStrong: '#d9c3cd',
  borderLight: '#f0e4e9',
  borderLighter: '#f5ebef',
  fill: '#f4e8ed',
  fillDarker: '#e8d4dd',
  fillDark: '#eee0e6',
  fillLight: '#f8f0f3',
  fillLighter: '#fbf6f8',
  fillExtraLight: '#fdfafb',
  brandLogoStart: '#d66b94',
  brandSignal: '#dfa0b8',
}

const NIGHT_DARK_SURFACE: ThemeSurfacePalette = {
  canvas: '#071820',
  paper: '#0d242c',
  ink: '#06151b',
  inkElevated: '#113039',
  text: '#eef7f6',
  textRegular: '#c6d7d4',
  textMuted: '#9fb5b1',
  textPlaceholder: '#91aaa5',
  textDisabled: '#718a86',
  line: '#28434a',
  lineStrong: '#3a5960',
  borderLight: '#223b42',
  borderLighter: '#1a3239',
  fill: '#17333a',
  fillDarker: '#204047',
  fillDark: '#19383f',
  fillLight: '#143039',
  fillLighter: '#102a32',
  fillExtraLight: '#0f2730',
  brandLogoStart: '#43c8ba',
  brandSignal: '#4aa99f',
}

const AURORA_DARK_SURFACE: ThemeSurfacePalette = {
  canvas: '#100d23',
  paper: '#18142f',
  ink: '#0d0a1d',
  inkElevated: '#231b45',
  text: '#f3efff',
  textRegular: '#d3caeb',
  textMuted: '#aaa0c4',
  textPlaceholder: '#9b90b8',
  textDisabled: '#7f759d',
  line: '#373052',
  lineStrong: '#4d446b',
  borderLight: '#312a4a',
  borderLighter: '#29233f',
  fill: '#282143',
  fillDarker: '#342b51',
  fillDark: '#2d2549',
  fillLight: '#241e3d',
  fillLighter: '#201a37',
  fillExtraLight: '#1c1733',
  brandLogoStart: '#a987ff',
  brandSignal: '#896fc7',
}

const GRAPHITE_DARK_SURFACE: ThemeSurfacePalette = {
  canvas: '#111318',
  paper: '#191c22',
  ink: '#0d0f13',
  inkElevated: '#252a33',
  text: '#f2f4f7',
  textRegular: '#d0d5dd',
  textMuted: '#a7afbd',
  textPlaceholder: '#98a1b0',
  textDisabled: '#7c8594',
  line: '#353b45',
  lineStrong: '#4b535f',
  borderLight: '#303640',
  borderLighter: '#292e37',
  fill: '#282d35',
  fillDarker: '#343a44',
  fillDark: '#2d333c',
  fillLight: '#252a32',
  fillLighter: '#21252c',
  fillExtraLight: '#1d2128',
  brandLogoStart: '#b6c0cf',
  brandSignal: '#828d9d',
}

function fixedPreset(
  id: Exclude<ThemePresetId, 'system'>,
  name: string,
  zhName: string,
  description: string,
  primaryColor: string,
  mode: ResolvedThemeMode,
  surface: ThemeSurfacePalette,
): SelectableThemePreset {
  return {
    id,
    name,
    zhName,
    label: `${name} ${zhName}`,
    description,
    primaryColor,
    mode,
    preview: { primary: primaryColor, canvas: surface.canvas, paper: surface.paper },
    surfaces: { [mode]: surface },
  }
}

export const SYSTEM_THEME_PRESET: SelectableThemePreset = {
  id: 'system',
  name: 'System',
  zhName: '随行',
  label: 'System 随行',
  description: '跟随系统明暗，自动切换清爽与深色工作面',
  primaryColor: '#2563eb',
  mode: 'auto',
  preview: { primary: '#2563eb', canvas: SYSTEM_LIGHT_SURFACE.canvas, paper: SYSTEM_DARK_SURFACE.paper },
  surfaces: { light: SYSTEM_LIGHT_SURFACE, dark: SYSTEM_DARK_SURFACE },
}

export const VISUAL_THEME_PRESETS: readonly SelectableThemePreset[] = [
  fixedPreset('atlas', 'Atlas', '翡翠', '沉稳清晰的知识工作台', '#0f827a', 'light', ATLAS_LIGHT_SURFACE),
  fixedPreset('ocean', 'Ocean', '深海', '理性专注的蓝色界面', '#3e63dd', 'light', OCEAN_LIGHT_SURFACE),
  fixedPreset('violet', 'Violet', '智紫', '富有创造力的智能界面', '#7347bd', 'light', VIOLET_LIGHT_SURFACE),
  fixedPreset('ember', 'Ember', '暖焰', '温暖醒目的行动工作台', '#b45309', 'light', EMBER_LIGHT_SURFACE),
  fixedPreset('dawn', 'Dawn', '晨曦', '柔和从容的协作界面', '#b42362', 'light', DAWN_LIGHT_SURFACE),
  fixedPreset('night', 'Night', '夜航', '低眩光的深色工作环境', '#14b8a6', 'dark', NIGHT_DARK_SURFACE),
  fixedPreset('aurora', 'Aurora', '极光', '深靛紫光的探索界面', '#8b5cf6', 'dark', AURORA_DARK_SURFACE),
  fixedPreset('graphite', 'Graphite', '墨岩', '克制中性的石墨工作台', '#94a3b8', 'dark', GRAPHITE_DARK_SURFACE),
]

export const THEME_PRESETS: readonly SelectableThemePreset[] = [SYSTEM_THEME_PRESET, ...VISUAL_THEME_PRESETS]

export const DEFAULT_THEME_PRESET = VISUAL_THEME_PRESETS.find((preset) => preset.id === 'ocean')!

const THEME_PRESET_BY_ID = new Map(THEME_PRESETS.map((preset) => [preset.id, preset]))

/** ID 来自受控目录；未知 ID 属于调用方编程错误，直接失败。 */
export function getThemePreset(id: ThemePresetId): SelectableThemePreset {
  const preset = THEME_PRESET_BY_ID.get(id)
  if (!preset) {
    throw new Error(`Unknown theme preset: ${id}`)
  }
  return preset
}

/** System 解析系统明暗；固定主题必须拿到与自身 mode 对应的完整 surface。 */
export function resolveThemePreset(preset: ThemePreset, systemDark: boolean): ResolvedThemePreset {
  const appearance = preset.mode === 'auto' ? (systemDark ? 'dark' : 'light') : preset.mode
  const surface = preset.surfaces[appearance]
  if (!surface) {
    throw new Error(`Theme preset ${preset.id} has no ${appearance} surface`)
  }
  return { appearance, surface }
}

/** 旧自定义颜色没有专属工作面时，保留主色并使用同明暗的安全基础 surface。 */
export function resolveFallbackSurface(mode: ThemeMode, systemDark: boolean): ResolvedThemePreset {
  const appearance = mode === 'auto' ? (systemDark ? 'dark' : 'light') : mode
  return {
    appearance,
    surface: appearance === 'dark' ? SYSTEM_DARK_SURFACE : SYSTEM_LIGHT_SURFACE,
  }
}

export function createCustomThemePreset(
  primaryColor: string,
  mode: ThemeMode,
  systemDark: boolean,
): ThemePreset {
  const resolved = resolveFallbackSurface(mode, systemDark)
  return {
    id: 'custom',
    name: 'Custom',
    zhName: '自定义',
    label: 'Custom 自定义',
    description: '沿用升级前保存的主题颜色与明暗偏好',
    primaryColor,
    mode,
    preview: {
      primary: primaryColor,
      canvas: resolved.surface.canvas,
      paper: resolved.surface.paper,
    },
    surfaces: mode === 'auto'
      ? { light: SYSTEM_LIGHT_SURFACE, dark: SYSTEM_DARK_SURFACE }
      : { [mode]: resolved.surface },
  }
}
