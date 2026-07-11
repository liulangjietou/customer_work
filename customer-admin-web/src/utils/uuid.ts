/**
 * 生成 UUID v4。
 *
 * 坑：`crypto.randomUUID()` 要求浏览器"安全上下文"（HTTPS 或 localhost/127.0.0.1），
 * 通过局域网 IP + 纯 HTTP 访问（如 http://10.133.40.194:5174）不满足这个条件，
 * `crypto.randomUUID` 在这种场景下是 `undefined`，直接调用会抛
 * `TypeError: crypto.randomUUID is not a function`，导致组件 setup 中途崩溃
 * （实测会级联出 Vue 卸载阶段的 "Cannot read properties of null (reading 'parentNode')"）。
 *
 * `crypto.getRandomValues` 不受安全上下文限制，优先用它拼 UUID v4；连它都不存在的极端环境
 * 再退化到 Math.random（仅用作会话 ID，不用于安全敏感场景，弱随机性可接受）。
 */
export function generateUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
