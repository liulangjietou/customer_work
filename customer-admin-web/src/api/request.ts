import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/store/auth'
import type { Result } from '@/types/api'

// ResultCode 分段（与后端 common/result/ResultCode.java 保持一致）
const CODE_UNAUTHORIZED = 10001
const CODE_FORCE_CHANGE_PASSWORD = 20002

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
http.interceptors.response.use(((response: { data: Result<unknown> }) => {
  const body = response.data
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
  ElMessage.error(body.message || '请求失败')
  return Promise.reject(body)
}) as never, (error) => {
  ElMessage.error(error.message || '网络异常')
  return Promise.reject(error)
})

/** 统一走 Result<T> 拦截解包，业务代码直接拿到 data。 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>
}

export default http
