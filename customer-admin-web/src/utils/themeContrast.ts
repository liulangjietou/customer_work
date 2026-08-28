const HEX_COLOR_PATTERN = /^#([\da-f]{2})([\da-f]{2})([\da-f]{2})$/i

export const LIGHT_BUTTON_TEXT = '#ffffff'
export const DARK_BUTTON_TEXT = '#000000'

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
