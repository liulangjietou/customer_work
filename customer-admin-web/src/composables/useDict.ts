import { ref, type Ref } from 'vue'
import { fetchDictOptions, type DictOption } from '@/api/dict'

/**
 * 通用字典下拉 hook：按字典类型编码取启用选项，供各业务页面替代硬编码下拉。
 *
 * - 模块级缓存：同一类型整个 SPA 生命周期内只请求一次（字典是低频变更数据，改完刷新页面即可）。
 * - 硬编码兜底：接口失败或类型未配置（空数组）时保留 fallback——字典是展示增强，不能因为
 *   客服端库不可达就让业务页面的筛选框变成空白。
 */
const optionsCache = new Map<string, Promise<DictOption[]>>()

export function useDict(dictType: string, fallback: DictOption[] = []): { options: Ref<DictOption[]> } {
  const options = ref<DictOption[]>(fallback)

  let pending = optionsCache.get(dictType)
  if (!pending) {
    pending = fetchDictOptions(dictType).catch(() => {
      // 失败不缓存成功值：从缓存剔除，下一个使用方重试
      optionsCache.delete(dictType)
      return [] as DictOption[]
    })
    optionsCache.set(dictType, pending)
  }
  pending.then((list) => {
    if (list.length > 0) {
      options.value = list
    }
  })

  return { options }
}
