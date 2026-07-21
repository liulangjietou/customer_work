/**
 * 编解码纯函数集合：Base64（UTF-8 安全）、URL、Hex。均用浏览器内置能力实现，出错时抛出
 * 具体原因的 Error，由调用方统一展示（不弹窗，走输入框下方红字）。
 */

/** 文本 -> UTF-8 安全的 Base64（经典 btoa 只认 Latin1，中文等多字节字符需要先转成字节再编码）。 */
export function base64EncodeUtf8(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary)
}

/** Base64 -> 文本，非法 Base64 或解码后不是合法 UTF-8 都给出具体原因。 */
export function base64DecodeUtf8(base64: string): string {
  let binary: string
  try {
    binary = atob(base64.trim())
  } catch {
    throw new Error('不是合法的 Base64 字符串')
  }
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0))
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new Error('Base64 解码后的字节不是合法的 UTF-8 文本')
  }
}

/** URL 编码（encodeURIComponent，覆盖中文/特殊字符）。 */
export function urlEncode(text: string): string {
  return encodeURIComponent(text)
}

/** URL 解码，%后缺两位十六进制等非法序列会明确报错。 */
export function urlDecode(text: string): string {
  try {
    return decodeURIComponent(text)
  } catch {
    throw new Error('不是合法的 URL 编码内容（% 后必须跟随两位十六进制数字）')
  }
}

/** 文本 -> 十六进制（UTF-8 字节，小写）。 */
export function textToHex(text: string): string {
  const bytes = new TextEncoder().encode(text)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** 十六进制 -> 文本（按 UTF-8 解码），格式或编码非法都给出具体原因。 */
export function hexToText(hex: string): string {
  const cleaned = hex.replace(/\s+/g, '')
  if (!cleaned) return ''
  if (!/^[0-9a-fA-F]*$/.test(cleaned)) {
    throw new Error('包含非十六进制字符（只允许 0-9、a-f、A-F，空白会被忽略）')
  }
  if (cleaned.length % 2 !== 0) {
    throw new Error(`十六进制字符串长度必须是偶数（每两位表示一个字节），当前 ${cleaned.length} 位`)
  }
  const bytes = new Uint8Array(cleaned.length / 2)
  for (let i = 0; i < bytes.length; i += 1) {
    bytes[i] = parseInt(cleaned.substring(i * 2, i * 2 + 2), 16)
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new Error('解码后的字节不是合法的 UTF-8 文本')
  }
}
