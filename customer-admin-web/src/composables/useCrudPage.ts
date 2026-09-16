import { onScopeDispose, ref, watch } from 'vue'
import type { Ref } from 'vue'
import type { FormInstance } from 'element-plus'
import type { PageQuery, PageResult } from '@/types/api'
import { usePagedList } from './usePagedList'
import { useAuthStore } from '@/store/auth'
import { useAuthSubmissionScope } from './useAuthSubmissionScope'

import { useCrudForm, type CrudDialogMode } from './useCrudForm'

export type { CrudDialogMode } from './useCrudForm'

/**
 * 标准管理页 CRUD 选项。
 *
 * 设计约束（抽"逻辑"不抽"模板"）：
 * - 只收敛行为逻辑（分页加载/搜索/弹窗状态机/提交/删除确认），模板保持显式的
 *   el-table/el-form——列与表单是每页的本质差异，留在模板里才可读。
 * - api/request.ts 拦截器负责即时错误提示，usePagedList 保留页面错误与旧结果，供原地重试。
 * - 页面特有动作（测试连通性、启停、审批等）不进本 composable，留在页面里调
 *   loadList 刷新。
 */
export interface CrudPageOptions<VO, Q extends PageQuery, F extends object> {
  /** 分页查询接口 */
  page: (query: Q) => Promise<PageResult<VO>>
  /** 新建接口；页面无新建能力时不传 */
  create?: (form: F) => Promise<unknown>
  /** 编辑保存接口；页面无编辑能力时不传 */
  update?: (id: number, form: F) => Promise<unknown>
  /** 删除接口；页面无删除能力时不传 */
  remove?: (row: VO) => Promise<unknown>
  /**
   * 表单引用：由页面创建并绑定到模板 el-form 的 ref="formRef"，提交时用它做 rules 校验。
   * 之所以让页面持有而不是这里创建：模板 ref 属于视图层，且页面 script 若不直接使用该
   * 变量会触发 noUnusedLocals（字符串 ref 不被 vue-tsc 计为使用）。
   */
  formRef?: Ref<FormInstance | undefined>
  /** 查询条件初始值（handleSearch 只重置 pageNum，条件字段保留，与现有页面行为一致） */
  initQuery: () => Q
  /** 表单初始值（openCreate 时整体覆盖回填） */
  initForm: () => F
  /** 编辑回填：行数据 -> 表单（如"编辑时 apiKey 置空表示不修改"的差异逻辑在此表达） */
  toForm?: (row: VO) => F
  /** 行主键取值，默认取 row.id */
  rowId?: (row: VO) => number
  /** 删除确认文案 */
  deleteConfirm?: (row: VO) => string
  /**
   * 提交前置校验钩子（表单 rules 之外的页面特有校验，如"新建必须填 AppKey"）。
   * 返回 false 中断提交，提示由页面自行完成。
   */
  beforeSubmit?: (mode: CrudDialogMode, form: F) => boolean
  /** 成功提示文案，缺省与存量页面一致 */
  messages?: { created?: string; updated?: string; deleted?: string }
}

/**
 * 标准管理页 CRUD 状态机：列表分页加载 + 搜索 + 新建/编辑弹窗 + 删除确认。
 * 使用方式见试点页 views/aiconfig/ModelManage.vue。
 */
export function useCrudPage<VO, Q extends PageQuery, F extends object>(
  options: CrudPageOptions<VO, Q, F>,
) {
  const { loading, loadError, list, total, query, loadList, handleSearch } = usePagedList<VO, Q>(
    options,
  )
  const auth = useAuthStore()
  const captureSubmission = useAuthSubmissionScope()
  const formState = useCrudForm<VO, F>({ ...options, onSaved: loadList })
  let deleteGeneration = 0
  const deletingId = ref<number | null>(null)
  const rowId = options.rowId ?? ((row: VO) => (row as { id: number }).id)

  watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
    deleteGeneration += 1
    deletingId.value = null
  }, { flush: 'sync' })
  onScopeDispose(() => { deleteGeneration += 1 })

  /** API 拦截器已提示写入失败；事件入口消费拒绝并保留表单，避免再抛为页面未处理异常。 */
  async function tryWrite(action: Promise<unknown>): Promise<boolean> {
    try {
      await action
      return true
    } catch {
      return false
    }
  }

  async function handleDelete(row: VO) {
    if (!options.remove || deletingId.value !== null) {
      return
    }
    const id = rowId(row)
    const generation = ++deleteGeneration
    const isCurrentIdentity = captureSubmission()
    const isCurrent = () => isCurrentIdentity() && generation === deleteGeneration
    if (!isCurrent()) return
    deletingId.value = id
    try {
      try {
        await ElMessageBox.confirm(options.deleteConfirm?.(row) ?? '确认删除该条记录？', '提示', {
          type: 'warning',
        })
      } catch (error) {
        // Element Plus 用 reject 表达用户主动取消；这是正常交互，不应冒泡成未处理异常。
        const reason = error instanceof Error ? error.message : error
        if (reason === 'cancel' || reason === 'close') {
          return
        }
        throw error
      }
      if (!isCurrent()) return
      if (!await tryWrite(options.remove(row))) return
      if (!isCurrent()) return
      ElMessage.success(options.messages?.deleted ?? '删除成功')
      // 删除当前页最后一条时先回到上一页，避免成功后展示一个不存在的空页。
      const currentPage = query.pageNum ?? 1
      if (list.value.length === 1 && currentPage > 1) {
        query.pageNum = currentPage - 1
      }
      await loadList()
    } catch (error) {
      if (isCurrent()) throw error
    } finally {
      if (isCurrent()) deletingId.value = null
    }
  }

  return {
    ...formState,
    loading,
    loadError,
    deletingId,
    list,
    total,
    query,
    loadList,
    handleSearch,
    handleDelete,
  }
}
