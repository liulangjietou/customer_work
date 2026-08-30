import { reactive, ref } from 'vue'
import type { Ref } from 'vue'
import type { FormInstance } from 'element-plus'
import type { PageQuery, PageResult } from '@/types/api'

export type CrudDialogMode = 'create' | 'edit'

/**
 * 标准管理页 CRUD 选项。
 *
 * 设计约束（抽"逻辑"不抽"模板"）：
 * - 只收敛行为逻辑（分页加载/搜索/弹窗状态机/提交/删除确认），模板保持显式的
 *   el-table/el-form——列与表单是每页的本质差异，留在模板里才可读。
 * - 不包 try/catch 做错误提示：api/request.ts 拦截器已统一 ElMessage.error，
 *   这里只负责 finally 收 loading，保持全链路单点防御。
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
export function useCrudPage<VO, Q extends PageQuery, F extends object>(options: CrudPageOptions<VO, Q, F>) {
  const loading = ref(false)
  const loadError = ref<unknown>(null)
  const submitting = ref(false)
  const deletingId = ref<number | null>(null)
  const list = ref([]) as Ref<VO[]>
  const total = ref(0)
  const query = reactive(options.initQuery()) as Q

  const dialogVisible = ref(false)
  const dialogMode = ref<CrudDialogMode>('create')
  const editingId = ref<number | null>(null)
  const form = reactive(options.initForm()) as F
  let loadRequestId = 0

  const rowId = options.rowId ?? ((row: VO) => (row as { id: number }).id)

  async function loadList() {
    const requestId = ++loadRequestId
    loading.value = true
    try {
      const result = await options.page(query)
      if (requestId !== loadRequestId) {
        return
      }
      list.value = result.list
      total.value = result.total
      loadError.value = null
    } catch (error) {
      // request.ts 已负责全局错误提示；这里保留页面级状态，避免失败被伪装成“暂无数据”。
      if (requestId === loadRequestId) {
        loadError.value = error
      }
    } finally {
      // 快速切换筛选条件时，旧请求结束不能提前关闭新请求的 loading。
      if (requestId === loadRequestId) {
        loading.value = false
      }
    }
  }

  function handleSearch() {
    query.pageNum = 1
    return loadList()
  }

  function openCreate() {
    dialogMode.value = 'create'
    editingId.value = null
    Object.assign(form, options.initForm())
    dialogVisible.value = true
  }

  function openEdit(row: VO) {
    dialogMode.value = 'edit'
    editingId.value = rowId(row)
    Object.assign(form, options.toForm ? options.toForm(row) : options.initForm())
    dialogVisible.value = true
  }

  async function handleSubmit() {
    if (submitting.value) {
      return
    }
    submitting.value = true
    try {
      if (options.formRef) {
        const valid = await options.formRef.value?.validate().catch(() => false)
        if (!valid) {
          return
        }
      }
      if (options.beforeSubmit && !options.beforeSubmit(dialogMode.value, form)) {
        return
      }
      if (dialogMode.value === 'create' && options.create) {
        await options.create(form)
        ElMessage.success(options.messages?.created ?? '新建成功')
      } else if (dialogMode.value === 'edit' && options.update) {
        if (!editingId.value) {
          return
        }
        await options.update(editingId.value, form)
        ElMessage.success(options.messages?.updated ?? '保存成功')
      } else {
        return
      }
      dialogVisible.value = false
      await loadList()
    } finally {
      submitting.value = false
    }
  }

  async function handleDelete(row: VO) {
    if (!options.remove || deletingId.value !== null) {
      return
    }
    const id = rowId(row)
    deletingId.value = id
    try {
      try {
        await ElMessageBox.confirm(options.deleteConfirm?.(row) ?? '确认删除该条记录？', '提示', { type: 'warning' })
      } catch (error) {
        // Element Plus 用 reject 表达用户主动取消；这是正常交互，不应冒泡成未处理异常。
        const reason = error instanceof Error ? error.message : error
        if (reason === 'cancel' || reason === 'close') {
          return
        }
        throw error
      }
      await options.remove(row)
      ElMessage.success(options.messages?.deleted ?? '删除成功')
      // 删除当前页最后一条时先回到上一页，避免成功后展示一个不存在的空页。
      const currentPage = query.pageNum ?? 1
      if (list.value.length === 1 && currentPage > 1) {
        query.pageNum = currentPage - 1
      }
      await loadList()
    } finally {
      deletingId.value = null
    }
  }

  return {
    loading,
    loadError,
    submitting,
    deletingId,
    list,
    total,
    query,
    dialogVisible,
    dialogMode,
    editingId,
    form,
    loadList,
    handleSearch,
    openCreate,
    openEdit,
    handleSubmit,
    handleDelete,
  }
}
