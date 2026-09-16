import { getCurrentScope, onScopeDispose, reactive, ref, type Ref } from 'vue'

interface PagedQuery {
  pageNum?: number
  pageSize?: number
}

export interface PagedListOptions<VO, Q extends PagedQuery> {
  initQuery: () => Q
  page: (query: Q) => Promise<{ list: VO[]; total: number }>
}

/** 分页读取的公共状态；字段映射留在调用方，写入及表单流程仍归各业务页面。 */
export function usePagedList<VO, Q extends PagedQuery>(options: PagedListOptions<VO, Q>) {
  const loading = ref(false)
  const loadError = ref<unknown>(null)
  const list = ref([]) as Ref<VO[]>
  const total = ref(0)
  const query = reactive(options.initQuery()) as Q
  let loadRequestId = 0

  async function loadList() {
    const requestId = ++loadRequestId
    loading.value = true
    try {
      const result = await options.page({ ...query })
      if (requestId !== loadRequestId) return
      list.value = result.list
      total.value = result.total
      loadError.value = null
    } catch (error) {
      // 接口拦截器负责即时提示；页面状态保留错误和旧数据，供用户查看与重试。
      if (requestId === loadRequestId) loadError.value = error
    } finally {
      if (requestId === loadRequestId) loading.value = false
    }
  }

  function handleSearch() {
    query.pageNum = 1
    return loadList()
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      loadRequestId += 1
    })
  }

  return { loading, loadError, list, total, query, loadList, handleSearch }
}
