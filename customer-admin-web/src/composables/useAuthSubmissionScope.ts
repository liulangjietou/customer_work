import { onScopeDispose } from 'vue'
import { useAuthStore } from '@/store/auth'

/** 登录与改密提交共同绑定页面和发起身份；页面退出、同令牌重登均使旧结果失效。 */
export function useAuthSubmissionScope() {
  const auth = useAuthStore()
  let disposed = false
  onScopeDispose(() => { disposed = true })

  return () => {
    const generation = auth.loginGeneration
    const token = auth.token
    return () => !disposed && generation === auth.loginGeneration && token === auth.token
  }
}
