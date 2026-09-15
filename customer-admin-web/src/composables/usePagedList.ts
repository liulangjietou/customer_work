import { getCurrentScope, onScopeDispose, reactive, ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useAuthSubmissionScope } from './useAuthSubmissionScope'

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
  const auth = useAuthStore()
  const captureSubmission = useAuthSubmissionScope()

  // 请求拦截器隔离网络响应；页面自身还要清除上个身份留下的结果和错误。
  watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
    loadRequestId += 1
    list.value = []
    total.value = 0
    loadError.value = null
    loading.value = false
    Object.assign(query, options.initQuery())
  }, { flush: 'sync' })

  async function loadList() {
    const isCurrentIdentity = captureSubmission()
    if (!isCurrentIdentity()) return
    const requestId = ++loadRequestId
    const isCurrent = () => isCurrentIdentity() && requestId === loadRequestId
    loading.value = true
    try {
      const result = await options.page({ ...query })
      if (!isCurrent()) return
      list.value = result.list
      total.value = result.total
      loadError.value = null
    } catch (error) {
      // 接口拦截器负责即时提示；页面状态保留错误和旧数据，供用户查看与重试。
      if (isCurrent()) loadError.value = error
    } finally {
      if (isCurrent()) loading.value = false
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
