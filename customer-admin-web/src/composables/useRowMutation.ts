import { reactive, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useAuthSubmissionScope } from './useAuthSubmissionScope'

/** 行写操作共用进行中锁与身份边界；请求体、成功刷新和失败回滚仍由业务页面负责。 */
export function useRowMutation(permission: string) {
  const auth = useAuthStore()
  const captureSubmission = useAuthSubmissionScope()
  const pending = reactive(new Set<number>())
  let generation = 0

  watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
    generation += 1
    pending.clear()
  }, { flush: 'sync' })

  async function run(
    id: number,
    write: () => Promise<unknown>,
    onSuccess: () => void | Promise<void>,
    onFailure?: () => void,
  ) {
    if (pending.has(id) || !auth.hasPermission(permission)) return
    const currentGeneration = generation
    const isCurrentIdentity = captureSubmission()
    const isCurrent = () => isCurrentIdentity() && currentGeneration === generation
    if (!isCurrent()) return
    pending.add(id)
    try {
      try {
        await write()
      } catch {
        // 请求拦截器已经提示失败；事件入口消费拒绝，旧身份不能回滚新页面的状态。
        if (isCurrent()) onFailure?.()
        return
      }
      if (isCurrent()) await onSuccess()
    } finally {
      if (isCurrent()) pending.delete(id)
    }
  }

  return { isPending: (id: number) => pending.has(id), run }
}
