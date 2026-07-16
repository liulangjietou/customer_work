import axios, { type AxiosRequestConfig, type AxiosError } from 'axios'
import { showToast } from 'vant'
import router from '@/router'
import { useAuthStore } from '@/store/auth'

// customer-work-app（8080）无统一 Result 包装：成功直接返回 JSON body，失败用 HTTP 状态码
// （401 未登录/token 过期、403 越权、409 状态冲突、400 参数错），响应体里可能带 message 字段。
const HTTP_UNAUTHORIZED = 401

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error: AxiosError<{ message?: string }>) => {
    const status = error.response?.status
    const message = error.response?.data?.message

    if (status === HTTP_UNAUTHORIZED) {
      const auth = useAuthStore()
      auth.clear()
      // 用 replace 而不是 push：避免堆叠历史记录；若当前已在 /login 则不重复触发导航
      if (router.currentRoute.value.name !== 'Login') {
        router.replace({ name: 'Login' })
      }
      showToast(message || '登录已失效，请重新登录')
      return Promise.reject(error)
    }

    showToast(message || error.message || '请求失败')
    return Promise.reject(error)
  },
)

/** 无 Result 包装，响应拦截器已把 AxiosResponse 拆成裸 body，业务代码直接拿到 T。 */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>
}

export default http
