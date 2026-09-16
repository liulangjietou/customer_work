import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  deleteAgentDraft,
  getAgentDraft,
  saveAgentDraft,
  type AgentDraft,
  type AgentDraftConfiguration,
} from '@/api/agentDraft'
import { getRequestErrorMessage } from '@/api/request'
import { useAuthStore } from '@/store/auth'

/** 配置草稿只有服务端确认后才标记保存；编辑、刷新和多标签页冲突保留原输入。 */
export function useAgentDraftWorkflow(
  form: AgentDraftConfiguration,
  visible: Ref<boolean>,
  agentId: Ref<number | null>,
  applying: Ref<boolean>,
) {
  const auth = useAuthStore()
  const saving = ref(false)
  const error = ref('')
  const baseRevision = ref<number | null>(null)
  const updatedAtMs = ref<number | null>(null)
  const baseline = ref('')
  const version = ref(0)
  let id = ''
  let generation = 0
  const snapshot = () => JSON.stringify(form)
  const dirty = computed(() => visible.value && snapshot() !== baseline.value)

  function begin(draft?: AgentDraft, revision?: number) {
    generation += 1
    id = draft?.id ?? crypto.randomUUID()
    version.value = draft?.version ?? 0
    baseRevision.value = draft?.baseRevision ?? revision ?? null
    updatedAtMs.value = draft?.updatedAtMs ?? null
    baseline.value = snapshot()
    error.value = ''
    saving.value = false
  }

  async function save(): Promise<boolean> {
    if (saving.value || applying.value || !visible.value) return false
    const request = generation
    const content = snapshot()
    const configuration = JSON.parse(content) as AgentDraftConfiguration
    const requestData = {
      expectedVersion: version.value,
      agentId: agentId.value,
      baseRevision: baseRevision.value,
      configuration,
    }
    saving.value = true
    error.value = ''
    try {
      const result = await saveAgentDraft(id, requestData)
      if (request !== generation) return false
      version.value = result.version
      updatedAtMs.value = result.updatedAtMs
      baseline.value = content
      return true
    } catch (cause) {
      if (request !== generation) return false
      // 写入响应可能丢失。只在服务端内容与这次请求完全一致时承认已保存，不覆盖别的标签页。
      try {
        const found = await getAgentDraft(id)
        const normalize = (value: AgentDraftConfiguration) =>
          JSON.stringify(
            Object.entries(value)
              .filter(([, field]) => field != null)
              .sort(([left], [right]) => left.localeCompare(right)),
          )
        if (
          request === generation &&
          found.agentId === requestData.agentId &&
          found.baseRevision === requestData.baseRevision &&
          found.configuration != null &&
          normalize(found.configuration) === normalize(configuration)
        ) {
          version.value = found.version
          updatedAtMs.value = found.updatedAtMs
          baseline.value = content
          return true
        }
      } catch {
        /* 查询不可用时保持未确认状态与原输入，允许再次核对。 */
      }
      if (request === generation)
        error.value = getRequestErrorMessage(cause, '草稿保存尚未确认，请重试；当前内容已保留。')
      return false
    } finally {
      if (request === generation) saving.value = false
    }
  }

  async function confirmLeave(): Promise<boolean> {
    if (!auth.token) return true
    if (saving.value || applying.value) return false
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm(
        '当前修改尚未保存。可以继续编辑并保存草稿，或放弃这些修改。',
        '离开配置',
        {
          confirmButtonText: '放弃未保存修改',
          cancelButtonText: '继续编辑',
          type: 'warning',
        },
      )
      return true
    } catch {
      return false
    }
  }

  async function close() {
    if (await confirmLeave()) {
      visible.value = false
      generation += 1
    }
  }

  /** 正式保存成功后再清理对应草稿，清理失败不得把正式保存伪装成失败或重复执行。 */
  async function applied() {
    baseline.value = snapshot()
    if (!version.value) return
    try {
      await deleteAgentDraft(id, version.value)
    } catch {
      ElMessage.warning('智能体已保存，个人草稿暂未清理。可在“我的草稿”中核对后删除。')
    }
  }

  function beforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value && !saving.value && !applying.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  onBeforeRouteLeave(confirmLeave)
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => {
    generation += 1
    window.removeEventListener('beforeunload', beforeUnload)
  })
  watch(
    () => auth.token,
    () => {
      generation += 1
      visible.value = false
      error.value = ''
      saving.value = false
    },
    { flush: 'sync' },
  )
  return {
    saving,
    error,
    dirty,
    version,
    baseRevision,
    updatedAtMs,
    begin,
    save,
    close,
    applied,
  }
}
