import { ref, watch } from 'vue'
import type { Ref } from 'vue'

const STORAGE_PREFIX = 'devtools:'

/**
 * 单个工具输入字段的 localStorage 持久化 ref：刷新/重开页面不丢，key 统一带 `devtools:` 前缀。
 *
 * 约定：AES 工具的密钥/IV 属于敏感信息，不接入本函数，直接用普通 ref——这是本工具箱唯一的
 * 持久化例外，其余所有工具输入（含哈希 HMAC 密钥）按验收要求一律持久化。
 */
export function usePersistedRef<T>(key: string, defaultValue: T): Ref<T> {
  const storageKey = STORAGE_PREFIX + key
  let initial = defaultValue
  try {
    const raw = localStorage.getItem(storageKey)
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
