import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import router from '@/router'
import { useAuthStore } from '@/store/auth'
import type { Result } from '@/types/api'

/** 需要在组件内就地呈现错误的请求，可关闭全局顶部消息。 */
export interface AppRequestConfig extends AxiosRequestConfig {
  suppressErrorMessage?: boolean
}

// ResultCode 分段（与后端 common/result/ResultCode.java 保持一致）
const CODE_UNAUTHORIZED = 10001
const CODE_FORCE_CHANGE_PASSWORD = 20002

/** 同步 LLM 接口专用超时：模型调用远慢于普通 CRUD，30s 全局默认不够用（对齐定时任务手动触发的 180s）。 */
export const LLM_TIMEOUT_MS = 180000

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = auth.token
  }
  return config
})

// 拦截器把 AxiosResponse<Result<T>> 拆箱为 T 直接返回，与 axios 自身的类型声明（要求返回
// AxiosResponse）不一致，是该封装模式的既有取舍，用 any 顶掉这一层类型摩擦。
http.interceptors.response.use(((response: { data: Result<unknown> | Blob; config: AppRequestConfig }) => {
  // 二进制下载（responseType: 'blob'，如 SQL 查询导出 xlsx）响应体不是 Result 包装，
  // 原样透传整个 response，交给下面的 download() 解析 Content-Disposition 后再落盘，
  // 不复用下面的 Result 拆箱逻辑（body.code 在 Blob 上访问不到，会被误判成请求失败）。
  if (response.config.responseType === 'blob') {
    return response
  }
  const body = response.data as Result<unknown>
  if (body.code === 0) {
    return body.data
  }
  if (body.code === CODE_UNAUTHORIZED) {
    const auth = useAuthStore()
    auth.clear()
    // 用 replace 而不是 push：避免堆叠历史记录；若当前已在 /login 则不重复触发导航，
    // 防止与其他地方（如 router.beforeEach 里拉菜单失败）发起的导航形成并发冲突，
    // 那种并发导航实测会在 Vue 卸载中的组件上抛出 "parentNode" 空引用异常。
    if (router.currentRoute.value.name !== 'Login') {
      router.replace({ name: 'Login', query: { redirect: router.currentRoute.value.fullPath } })
    }
    ElMessage.error(body.message || '登录已失效，请重新登录')
    return Promise.reject(body)
  }
  if (body.code === CODE_FORCE_CHANGE_PASSWORD) {
    router.push('/change-password')
    return Promise.reject(body)
  }
  if (!response.config.suppressErrorMessage) {
    ElMessage.error(body.message || '请求失败')
  }
  return Promise.reject(body)
}) as never, (error) => {
  // 非 2xx 的响应体同样是 Result 包装（如额度用尽返回 429 + code 40043），优先显示后端文案：
  // 只读 error.message 的话用户看到的是 "Request failed with status code 429"，
  // 而真正该看的"最近 60 分钟内已达 N 次上限"就被吞掉了
  const body = error.response?.data as Result<unknown> | undefined
  const config = error.config as AppRequestConfig | undefined
  if (!config?.suppressErrorMessage) {
    ElMessage.error(body?.message || error.message || '网络异常')
  }
  return Promise.reject(error)
})

/** 统一走 Result<T> 拦截解包，业务代码直接拿到 data。 */
export function request<T>(config: AppRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>
}

/** 就地错误展示优先取标准 Result 文案，再回退 Axios/业务异常的顶层 message。 */
export function getRequestErrorMessage(error: unknown, fallback: string): string {
  if (!error || typeof error !== 'object') {
    return fallback
  }
  const responseMessage = (error as {
    response?: { data?: { message?: unknown } }
  }).response?.data?.message
  if (typeof responseMessage === 'string' && responseMessage.trim()) {
    return responseMessage.trim()
  }
  const message = (error as { message?: unknown }).message
  return typeof message === 'string' && message.trim() ? message.trim() : fallback
}

/** 从 Content-Disposition 头解析文件名，兼容 filename="x.xlsx" 与 RFC 5987 filename*=UTF-8''x.xlsx 两种形式。 */
function parseFilename(disposition: string | undefined): string | null {
  if (!disposition) {
    return null
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8Match) {
    return decodeURIComponent(utf8Match[1])
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition)
  return plainMatch ? plainMatch[1] : null
}

/**
 * 二进制内容获取（如对话附件原文件，用于图片内联预览的 objectURL）：与 download() 同样以
 * responseType: 'blob' 请求（带 Authorization header，绕过 Result 拆箱拦截），但只返回 Blob，
 * 不触发浏览器保存——调用方自己决定拿这个 Blob 做 <img> 预览还是别的用途。
 */
export async function requestBlob(config: AxiosRequestConfig): Promise<Blob> {
  const response = await http.request<Blob, AxiosResponse<Blob>>({ ...config, responseType: 'blob' })
  return response.data
}

/**
 * 二进制文件下载（如 SQL 查询结果导出 xlsx）：以 responseType: 'blob' 请求，跳过上面拦截器的
 * Result 拆箱，直接拿原始 AxiosResponse<Blob> 触发浏览器保存。文件名优先取响应头
 * Content-Disposition，取不到则用调用方传入的兜底名。
 *
 * 若后端在导出场景下抛业务异常（如权限不足/参数缺失），仍会按 Result 包装返回
 * content-type: application/json 的 JSON 体——此时 axios 依然把它当二进制读成 Blob，
 * 必须显式识别 content-type 再转文本解析，否则会把错误信息当文件内容下载成一个打不开的 xlsx。
 */
export async function download(config: AxiosRequestConfig, fallbackFilename: string): Promise<void> {
  const response = await http.request<Blob, AxiosResponse<Blob>>({ ...config, responseType: 'blob' })
  const contentType = response.headers['content-type'] as string | undefined
  if (contentType && contentType.includes('application/json')) {
    const text = await response.data.text()
    const body = JSON.parse(text) as Result<unknown>
    ElMessage.error(body.message || '导出失败')
    throw body
  }
  const filename = parseFilename(response.headers['content-disposition'] as string | undefined) || fallbackFilename
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export default http
