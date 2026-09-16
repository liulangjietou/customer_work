import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useAuthSubmissionScope } from './useAuthSubmissionScope'

/** 多资源查询以完整快照更新：失败保留旧值，旧请求不能覆盖新查询或新身份。 */
export function useQueryState<T>(query: () => Promise<T>, initial: () => T) {
  const data = ref(initial()) as Ref<T>
  const loading = ref(false)
  const error = ref<unknown>(null)
  const loaded = ref(false)
  const auth = useAuthStore()
  const captureSubmission = useAuthSubmissionScope()
  let requestId = 0

  function reset() {
    requestId += 1
    data.value = initial()
    loading.value = false
    error.value = null
    loaded.value = false
  }

  watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], reset,
    { flush: 'sync' })
  onScopeDispose(() => { requestId += 1 })

  async function load(): Promise<T | undefined> {
    const currentIdentity = captureSubmission()
    if (!currentIdentity()) return
    const id = ++requestId
    const current = () => currentIdentity() && id === requestId
    loading.value = true
    try {
      const result = await query()
      if (!current()) return
      data.value = result
      loaded.value = true
      error.value = null
      return result
    } catch (failure) {
      // 即时提示归请求层；此处消费异常并保留可重试状态，避免把故障显示成空数据。
      if (current()) error.value = failure
    } finally {
      if (current()) loading.value = false
    }
  }

  return { data, loading, error, loaded, load, reset }
}
