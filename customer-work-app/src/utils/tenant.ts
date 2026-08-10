/**
 * H5 端的租户标识解析。
 *
 * 登录与注册是仅有的两个需要客户端提供租户线索的接口——那时还没有登录态可依据。
 * 登录成功后租户被焊进 JWT，后续请求一律以令牌里的为准，这里存的值不再参与鉴权，
 * 所以它被改写也不构成越权（改了只会导致登录失败或登进另一个自己本就有账号的租户）。
 *
 * 来源优先级：URL 参数 > 本地缓存 > 构建期配置。
 * 接入方通常把 H5 嵌在自己站点里，首次带 ?tenant=xxx 进来即可，之后由缓存接管。
 */

const STORAGE_KEY = 'cw_tenant_code'
const QUERY_KEY = 'tenant'

/** 从 URL 读租户并落缓存；应在应用启动时调用一次。 */
export function captureTenantFromUrl(): void {
  const fromQuery = new URLSearchParams(window.location.search).get(QUERY_KEY)
  if (fromQuery && fromQuery.trim()) {
    localStorage.setItem(STORAGE_KEY, fromQuery.trim())
  }
}

/** 当前租户编码；未知时返回 undefined，由后端回落默认租户。 */
export function currentTenantCode(): string | undefined {
  const cached = localStorage.getItem(STORAGE_KEY)
  if (cached && cached.trim()) {
    return cached.trim()
  }
  const configured = import.meta.env.VITE_TENANT_CODE as string | undefined
  return configured && configured.trim() ? configured.trim() : undefined
}

/** 退出登录时清理，避免换租户登录时沿用上一个租户。 */
export function clearTenantCode(): void {
  localStorage.removeItem(STORAGE_KEY)
}
