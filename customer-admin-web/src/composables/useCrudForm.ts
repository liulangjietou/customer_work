import { onScopeDispose, reactive, ref, watch, type Ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { useAuthSubmissionScope } from './useAuthSubmissionScope'

export type CrudDialogMode = 'create' | 'edit'

export interface CrudFormOptions<VO, F extends object, K extends string | number = number> {
  create?: (form: F) => Promise<unknown>
  update?: (id: K, form: F) => Promise<unknown>
  formRef?: Ref<FormInstance | undefined>
  initForm: () => F
  toForm?: (row: VO) => F
  /** 使用真实行标识；例如配额等级是字符串编码，不需要伪造数字主键。 */
  rowId?: (row: VO) => K
  beforeSubmit?: (mode: CrudDialogMode, form: F) => boolean
  messages?: { created?: string; updated?: string }
  onSaved?: () => void | Promise<void>
}

/** 弹窗提交独立于列表形态，供分页页、菜单树和两级字典共用目标及身份边界。 */
export function useCrudForm<VO, F extends object, K extends string | number = number>(
  options: CrudFormOptions<VO, F, K>,
) {
  const auth = useAuthStore()
  const captureSubmission = useAuthSubmissionScope()
  let generation = 0
  const submitting = ref(false)
  const dialogVisible = ref(false)
  const dialogMode = ref<CrudDialogMode>('create')
  const editingId = ref<K | null>(null) as Ref<K | null>
  const form = reactive(options.initForm()) as F
  const rowId = options.rowId ?? ((row: VO) => (row as { id: K }).id)

  function invalidate() {
    generation += 1
    submitting.value = false
  }

  watch(dialogVisible, visible => { if (!visible) invalidate() }, { flush: 'sync' })
  watch([() => auth.loginGeneration, () => auth.token, () => auth.permissions.join('\0')], () => {
    invalidate()
    dialogVisible.value = false
    editingId.value = null
    Object.assign(form, options.initForm())
  }, { flush: 'sync' })
  onScopeDispose(invalidate)

  function openCreate() {
    invalidate()
    dialogMode.value = 'create'
    editingId.value = null
    Object.assign(form, options.initForm())
    dialogVisible.value = true
  }

  function openEdit(row: VO) {
    invalidate()
    dialogMode.value = 'edit'
    editingId.value = rowId(row)
    Object.assign(form, options.toForm ? options.toForm(row) : options.initForm())
    dialogVisible.value = true
  }

  /** 附属上传也绑定本次弹窗；关闭、重新打开或身份变化后，旧结果不再回填。 */
  function captureDialog() {
    const currentGeneration = generation
    const currentIdentity = captureSubmission()
    return () => currentIdentity() && generation === currentGeneration && dialogVisible.value
  }

  async function handleSubmit() {
    if (submitting.value || !dialogVisible.value) return
    const current = captureDialog()
    const mode = dialogMode.value
    const id = editingId.value
    if (!current()) return
    submitting.value = true
    try {
      if (options.formRef) {
        const valid = await options.formRef.value?.validate().catch(() => false)
        if (!valid) return
      }
      if (!current()) return
      if (options.beforeSubmit && !options.beforeSubmit(mode, form)) return
      if (!current()) return
      let write: (() => Promise<unknown>) | undefined
      if (mode === 'create' && options.create) {
        const create = options.create
        write = () => create({ ...form })
      } else if (mode === 'edit' && id && options.update) {
        const update = options.update
        write = () => update(id, { ...form })
      }
      if (!write) return
      try {
        await write()
      } catch {
        // 请求层负责即时反馈；当前弹窗保留输入，旧请求不能操作后来打开的弹窗。
        return
      }
      if (!current()) return
      ElMessage.success(mode === 'create'
        ? options.messages?.created ?? '新建成功'
        : options.messages?.updated ?? '保存成功')
      dialogVisible.value = false
      await options.onSaved?.()
    } finally {
      if (current()) submitting.value = false
    }
  }

  return { submitting, dialogVisible, dialogMode, editingId, form, openCreate, openEdit, handleSubmit, captureDialog }
}
