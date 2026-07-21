/**
 * JSON 工具的转义/去转义/Unicode 转换纯函数，从组件里抽出来便于单测与复用。
 *
 * 转义/去转义的语义（与主流 JSON 在线工具一致）：
 * - 转义：把任意原文文本变成一个"可以安全地当成 JSON 字符串值内容"的转义串，用 JSON.stringify
 *   实现（自动处理引号/反斜杠/控制字符，并整体加上外层双引号）。
 * - 去转义：把一个转义串（可以带外层双引号，也可以不带）还原成原文——不带外层引号时视为"转义串
 *   本体"，补上引号后按 JSON 字符串语法解析。
 */

/** 转义：原文 -> 可安全嵌入 JSON 的转义字符串（含外层双引号）。 */
export function escapeJsonText(raw: string): string {
  return JSON.stringify(raw)
}

/** 去转义：转义字符串（带或不带外层双引号）-> 还原后的原文。 */
export function unescapeJsonText(raw: string): string {
  const trimmed = raw.trim()
  const alreadyWrapped = trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
  const candidate = alreadyWrapped ? trimmed : `"${trimmed}"`
  try {
    const value = JSON.parse(candidate)
    if (typeof value !== 'string') {
      throw new Error('解析结果不是字符串')
    }
    return value
  } catch {
    throw new Error('内容不是合法的转义字符串（无法按 JSON 字符串语法解析，检查引号/反斜杠是否匹配）')
  }
}

/** Unicode 转义序列（\uXXXX）解码成真实字符（如中文），不影响其它内容。 */
export function decodeUnicodeEscapes(raw: string): string {
  return raw.replace(/\\u([0-9a-fA-F]{4})/g, (_match, hex) => String.fromCharCode(parseInt(hex, 16)))
}
