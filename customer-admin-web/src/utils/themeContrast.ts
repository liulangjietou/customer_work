const HEX_COLOR_PATTERN = /^#([\da-f]{2})([\da-f]{2})([\da-f]{2})$/i

export const LIGHT_BUTTON_TEXT = '#ffffff'
export const DARK_BUTTON_TEXT = '#000000'

export interface SolidColorPalette {
  base: string
  hover: string
  active: string
  onColor: string
}

/**
 * 在主题色实心按钮上选择对比度更高的黑/白文字。
 * 黑白两端至少有一端满足 WCAG AA 的 4.5:1，避免中间亮度主题色出现临界不可读状态。
 */
export function chooseButtonTextColor(background: string): string {
  const backgroundLuminance = relativeLuminance(background)
  if (backgroundLuminance === null) {
    return LIGHT_BUTTON_TEXT
  }
  const whiteContrast = contrastFromLuminance(backgroundLuminance, 1)
  const blackContrast = contrastFromLuminance(backgroundLuminance, 0)
  return whiteContrast >= blackContrast ? LIGHT_BUTTON_TEXT : DARK_BUTTON_TEXT
}

/** 返回两个六位十六进制颜色的 WCAG 对比度；格式非法时返回 null。 */
export function colorContrastRatio(first: string, second: string): number | null {
  const firstLuminance = relativeLuminance(first)
  const secondLuminance = relativeLuminance(second)
  if (firstLuminance === null || secondLuminance === null) {
    return null
  }
  return contrastFromLuminance(firstLuminance, secondLuminance)
}

/**
 * 保留原色相，向黑或白中对比度更高的一端收敛，直到满足给定 WCAG 阈值。
 * 用于把用户保存的品牌色转换为可安全承载正文链接和焦点环的前景色。
 */
export function ensureColorContrast(foreground: string, background: string, minimumRatio = 4.5): string {
  const foregroundRgb = parseHexColor(foreground)
  const backgroundLuminance = relativeLuminance(background)
  if (!foregroundRgb || backgroundLuminance === null) {
    return '#3e63dd'
  }
  if ((colorContrastRatio(foreground, background) ?? 0) >= minimumRatio) {
    return foreground.toLowerCase()
  }

  const blackContrast = contrastFromLuminance(backgroundLuminance, 0)
  const whiteContrast = contrastFromLuminance(backgroundLuminance, 1)
  const target = blackContrast >= whiteContrast ? [0, 0, 0] : [255, 255, 255]
  for (let step = 1; step <= 100; step += 1) {
    const ratio = step / 100
    const candidate = rgbToHex(foregroundRgb.map((channel, index) => (
      Math.round(channel + (target[index] - channel) * ratio)
    )))
    if ((colorContrastRatio(candidate, background) ?? 0) >= minimumRatio) {
      return candidate
    }
  }
  return rgbToHex(target)
}

/** 实心交互色：hover/active 始终向文字的反方向移动，因此不会牺牲文字对比度。 */
export function createSolidColorPalette(background: string): SolidColorPalette {
  const base = parseHexColor(background) ? background.toLowerCase() : '#3e63dd'
  const onColor = chooseButtonTextColor(base)
  const target = onColor === DARK_BUTTON_TEXT ? [255, 255, 255] : [0, 0, 0]
  return {
    base,
    hover: mixHexColor(base, target, 0.08),
    active: mixHexColor(base, target, 0.14),
    onColor,
  }
}

function parseHexColor(color: string): [number, number, number] | null {
  const matched = HEX_COLOR_PATTERN.exec(color)
  if (!matched) return null
  return [
    Number.parseInt(matched[1], 16),
    Number.parseInt(matched[2], 16),
    Number.parseInt(matched[3], 16),
  ]
}

function rgbToHex(channels: number[]): string {
  return `#${channels.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`
}

function mixHexColor(source: string, target: number[], ratio: number): string {
  const sourceRgb = parseHexColor(source)
  if (!sourceRgb) return '#3e63dd'
  return rgbToHex(sourceRgb.map((channel, index) => (
    Math.round(channel + (target[index] - channel) * ratio)
  )))
}

function relativeLuminance(color: string): number | null {
  const matched = HEX_COLOR_PATTERN.exec(color)
  if (!matched) {
    return null
  }
  const channelToLinear = (hex: string) => {
    const channel = Number.parseInt(hex, 16) / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  }
  return (
    0.2126 * channelToLinear(matched[1])
    + 0.7152 * channelToLinear(matched[2])
    + 0.0722 * channelToLinear(matched[3])
  )
}

function contrastFromLuminance(first: number, second: number): number {
  const lighter = Math.max(first, second)
  const darker = Math.min(first, second)
  return (lighter + 0.05) / (darker + 0.05)
}
