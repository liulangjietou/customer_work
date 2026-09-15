import { inject, ref, watch, type InjectionKey, type Ref } from 'vue'

const STORAGE_PREFIX = 'devtools:v2:'
export const TOOL_STORAGE_OWNER: InjectionKey<Readonly<Ref<string | null>>> = Symbol('tool-storage-owner')

/**
 * 输入按已确认的账号和租户视角持久化；无归属的历史键原样保留，但不能自动归给当前用户。
 * 未知账号只提供本次页面内的输入。AES 密钥/IV、JWT、证书私钥与密码继续使用普通 ref。
 */
export function usePersistedRef<T>(key: string, defaultValue: T): Ref<T> {
  const owner = inject(TOOL_STORAGE_OWNER, null)
  const initialOwner = owner?.value
  const storageKey = initialOwner ? STORAGE_PREFIX + initialOwner + ':' + key : null
  let initial = defaultValue
  try {
    const raw = storageKey ? localStorage.getItem(storageKey) : null
    if (raw !== null) {
      initial = JSON.parse(raw) as T
    }
  } catch {
    // 历史脏数据或手工改过导致解析失败，静默回退默认值，不影响工具正常使用
  }

  const state = ref(initial) as Ref<T>
  watch(
    state,
    (value) => {
      if (!storageKey || owner?.value !== initialOwner) return
      try {
        localStorage.setItem(storageKey, JSON.stringify(value))
      } catch {
        // 隐私模式/容量超限等导致写入失败，静默忽略——持久化是体验增强，不是功能前提
      }
    },
    { deep: true },
  )
  return state
}
