<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import { getAgentDraftTrial, listAgentDraftTrials, previewAgentDraftTrial, startAgentDraftTrial,
  type AgentDraftTrialPhase, type AgentDraftTrialPreview, type AgentDraftTrialReceipt,
  type AgentDraftTrialSummary } from '@/api/agentDraftTrial'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ draftId: string; title: string }>()
const auth = useAuthStore()
const preview = ref<AgentDraftTrialPreview | null>(null)
const history = ref<AgentDraftTrialSummary[]>([])
const receipt = ref<AgentDraftTrialReceipt | null>(null)
const input = ref('')
const trialId = ref('')
const error = ref('')
const historyError = ref('')
const loading = ref(false)
const submitting = ref(false)
const checking = ref(false)
const waiting = ref(false)
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined
let requests = new AbortController()
const phaseNames: Record<AgentDraftTrialPhase, string> = {
  RUNNING: '执行中', SUCCEEDED: '回答已完成', FAILED: '试用失败', UNKNOWN: '结果未知',
}
const errors: Record<string, string> = {
  TRIAL_TIMEOUT: '本次试用已超时，可以调整问题后发起新的试用。',
  TRIAL_TOOL_RESTRICTED: '模型请求了本次范围之外的工具，执行已被拦截。',
  TRIAL_QUOTA_EXCEEDED: '当前账号的 AI 用量额度不足，请稍后再试。',
  TRIAL_EXECUTION_FAILED: '本次模型或资源调用未完成，请检查配置后再试。',
  TRIAL_RESULT_UNKNOWN: '未能确认最终结果，系统不会自动重放这次模型调用。',
}
const PRE_ACCEPTANCE_REJECTIONS = new Set([20001, 30001, 30002, 30003, 30016])
function rejectedBeforeAcceptance(cause: unknown) {
  const failure = cause as { code?: number; response?: { data?: { code?: number } } } | null
  return PRE_ACCEPTANCE_REJECTIONS.has(failure?.code ?? failure?.response?.data?.code ?? -1)
}
const unresolved = computed(() => !!trialId.value && (!receipt.value || receipt.value.phase === 'RUNNING'))
const canStart = computed(() => !!preview.value && !!input.value.trim() && !loading.value
  && !submitting.value && !checking.value && !unresolved.value)
const storageKey = () => `cw-agent-draft-trial:${props.draftId}`
function remember(id: string) {
  try { if (id) sessionStorage.setItem(storageKey(), id); else sessionStorage.removeItem(storageKey()) } catch { /* 历史回执仍在服务端。 */ }
}
function remembered() {
  try { return sessionStorage.getItem(storageKey()) ?? '' } catch { return '' }
}
function stopWaiting() {
  waiting.value = false
  if (timer) clearTimeout(timer)
  timer = undefined
}
function active(request: number, id?: string) {
  return request === generation && visible.value && (!id || id === trialId.value)
}
function applyReceipt(value: AgentDraftTrialReceipt) {
  // 重复 PUT 的迟到 RUNNING 不能覆盖后来 GET 已核实的终态。
  if (receipt.value?.id === value.id && receipt.value.phase !== 'RUNNING' && value.phase === 'RUNNING') return
  receipt.value = value
  error.value = ''
  if (!input.value) input.value = value.input
  if (value.phase !== 'RUNNING') { stopWaiting(); remember('') }
}
async function load() {
  const request = generation
  loading.value = true
  error.value = ''; historyError.value = ''
  const result = await Promise.allSettled([
    previewAgentDraftTrial(props.draftId, requests.signal), listAgentDraftTrials(props.draftId, requests.signal),
  ])
  if (!active(request)) return
  loading.value = false
  if (result[0].status === 'fulfilled') preview.value = result[0].value
  else { preview.value = null; error.value = getRequestErrorMessage(result[0].reason, '试用配置暂时无法核对') }
  if (result[1].status === 'fulfilled') history.value = result[1].value
  else historyError.value = '历史记录暂时无法加载'
  if (trialId.value) void check()
}
async function check() {
  if (!trialId.value || checking.value) return
  const request = generation
  const id = trialId.value
  checking.value = true
  try {
    const found = await getAgentDraftTrial(props.draftId, id, requests.signal)
    if (active(request, id)) { applyReceipt(found); return true }
  } catch (cause) {
    if (active(request, id)) error.value = getRequestErrorMessage(cause, '暂时无法核对回执，问题与原请求标识已保留')
    return false
  } finally {
    if (active(request, id)) {
      checking.value = false
      if (waiting.value && unresolved.value) timer = setTimeout(() => { void check() }, 1500)
    }
  }
}
async function start() {
  if (!canStart.value || !preview.value) return
  const request = generation
  const id = crypto.randomUUID()
  const data = { expectedDraftVersion: preview.value.draftVersion, input: input.value }
  trialId.value = id; receipt.value = null; remember(id)
  submitting.value = true; waiting.value = true; error.value = ''
  timer = setTimeout(() => { void check() }, 1500)
  try {
    const result = await startAgentDraftTrial(props.draftId, id, data, requests.signal)
    if (active(request, id)) applyReceipt(result)
  } catch (cause) {
    if (active(request, id) && unresolved.value) {
      error.value = getRequestErrorMessage(cause, '响应尚未确认，请用原标识核对回执')
      const recovered = await check()
      if (active(request, id) && recovered === false && rejectedBeforeAcceptance(cause)) {
        stopWaiting(); remember(''); trialId.value = ''; preview.value = null
        error.value = getRequestErrorMessage(cause, '试用尚未受理，请重新核对配置')
      }
    }
  } finally {
    if (active(request) && (!trialId.value || trialId.value === id)) submitting.value = false
  }
}
async function showHistory(item: AgentDraftTrialSummary) {
  if (submitting.value || checking.value || unresolved.value) return
  stopWaiting()
  receipt.value = null; input.value = ''; trialId.value = item.id
  await check()
}
function prepareAnother() {
  if (unresolved.value || submitting.value || checking.value || loading.value) return
  trialId.value = ''; receipt.value = null; error.value = ''
  void load()
}
watch([visible, () => props.draftId], ([open]) => {
  generation += 1
  stopWaiting(); requests.abort(); requests = new AbortController()
  preview.value = null; receipt.value = null; history.value = []; error.value = ''; historyError.value = ''
  input.value = ''; loading.value = false; submitting.value = false; checking.value = false
  trialId.value = open ? remembered() : ''
  if (open && props.draftId) void load()
}, { flush: 'sync' })
watch([() => auth.token, () => auth.loginGeneration, () => auth.permissions.join('\0')], () => {
  remember(''); visible.value = false
}, { flush: 'sync' })
onBeforeUnmount(() => { generation += 1; stopWaiting(); requests.abort() })
</script>

<template>
  <el-drawer v-model="visible" title="草稿受控试用" size="min(760px, 100vw)" class="agent-trial-drawer">
    <div class="trial-intro">
      <h3>{{ title || '个人配置草稿' }}</h3>
      <p>使用已保存的个人草稿进行独立试用。会实际调用模型并计入用量；正式配置与会话不受影响。</p>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section v-loading="loading" class="trial-scope" aria-label="本次执行范围" :aria-busy="loading">
      <template v-if="preview">
        <header><strong>本次执行范围</strong><el-tag>草稿 v{{ preview.draftVersion }}</el-tag></header>
        <dl>
          <dt>模型</dt><dd>{{ preview.models.join(' → ') }}</dd>
          <dt>知识版本</dt><dd>{{ preview.knowledgeBases.map(item => `${item.name} · 版本 ${item.versionId}`).join('、') || '未绑定' }}</dd>
          <dt>Skill 版本</dt><dd>{{ preview.skills.map(item => `${item.name} · 版本 ${item.versionId}`).join('、') || '未绑定' }}</dd>
        </dl>
        <ul v-if="preview.restrictions.length" class="trial-restrictions"><li v-for="item in preview.restrictions" :key="item">{{ item }}</li></ul>
      </template>
      <div v-else-if="!loading" class="trial-unavailable">配置尚未通过核对。<el-button link type="primary" @click="load">重新核对</el-button></div>
    </section>
    <section class="trial-question" aria-label="试用问题">
      <label for="agent-trial-input">试用问题</label>
      <el-input id="agent-trial-input" v-model="input" type="textarea" :rows="4" maxlength="4000" show-word-limit
        :disabled="submitting || unresolved" placeholder="例如：客户申请退款，需要补充哪些信息？" />
      <div class="trial-actions">
        <el-button type="primary" :disabled="!canStart" :loading="submitting" @click="start">开始试用</el-button>
        <el-button v-if="trialId" :loading="checking" @click="check">核对回执</el-button>
        <el-button v-if="waiting" @click="stopWaiting">停止自动核对</el-button>
        <el-button v-if="receipt && !unresolved" :disabled="submitting" @click="prepareAnother">准备新的试用</el-button>
      </div>
      <p v-if="unresolved" class="trial-note" role="status">{{ receipt ? '服务端已受理，正在执行。' : '正在确认受理与结果。' }}关闭面板不会撤回服务端执行，可稍后用原回执继续核对。</p>
    </section>
    <section v-if="receipt" class="trial-result" aria-label="试用结果">
      <header><h4>{{ phaseNames[receipt.phase] }}</h4><span>草稿 v{{ receipt.draftVersion }}</span></header>
      <el-alert v-if="receipt.configurationMatch !== 'MATCH'" :title="receipt.configurationMatch === 'CHANGED'
        ? '当前草稿或资源已变化，此结果仅作为历史记录' : '当前资源无法核对，不能确认此结果对应现在的配置'" type="warning" :closable="false" show-icon />
      <p v-if="receipt.errorCode" role="status">{{ errors[receipt.errorCode] || '本次试用未完成，请检查配置后重试。' }}</p>
      <p class="trial-note">问题：{{ receipt.input }}</p>
      <template v-if="receipt.result">
        <div class="trial-answer">{{ receipt.result.answer }}</div>
        <p v-if="receipt.result.answerTruncated" class="trial-note">回答超过保存上限，当前显示已保存的部分。</p>
        <p class="trial-note">耗时 {{ (receipt.result.durationMs / 1000).toFixed(1) }} 秒 · 请求工具：{{ receipt.result.attemptedTools.join('、') || '未请求' }}</p>
        <p class="trial-note">回答完成不等于通过发布评测。正式发布仍需核对对应版本的后端门禁与实例确认。</p>
      </template>
    </section>
    <section class="trial-history" aria-label="最近试用">
      <header><h4>最近试用</h4><el-button link type="primary" :disabled="loading" @click="load">刷新</el-button></header>
      <p v-if="historyError" role="alert">{{ historyError }}</p>
      <el-empty v-else-if="!loading && !history.length" description="暂无试用记录" :image-size="56" />
      <button v-for="item in history" :key="item.id" class="trial-history-row" :disabled="submitting || checking || unresolved" @click="showHistory(item)">
        <span>{{ item.inputExcerpt }}</span><small>v{{ item.draftVersion }} · {{ phaseNames[item.phase] }} · {{ new Date(item.acceptedAtMs).toLocaleString() }}</small>
      </button>
    </section>
  </el-drawer>
</template>

<style scoped>
.trial-intro h3 { margin: 0 0 8px; overflow-wrap: anywhere; }
.trial-intro p, .trial-note { color: var(--cw-text-muted); line-height: 1.7; font-size: 13px; }
.trial-scope { background: var(--cw-canvas); border: 1px solid var(--cw-line); border-radius: 12px; padding: 16px; margin: 18px 0; min-height: 80px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
header h4 { margin: 0; }
dl { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 10px; font-size: 13px; margin-bottom: 0; }
dt { color: var(--cw-text-muted); } dd { margin: 0; overflow-wrap: anywhere; }
.trial-restrictions { color: var(--cw-text-muted); font-size: 13px; line-height: 1.8; padding-left: 18px; margin-bottom: 0; }
.trial-question label { display: block; font-weight: 600; margin-bottom: 10px; }
.trial-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 14px; }
.trial-actions :deep(.el-button + .el-button) { margin-left: 0; }
.trial-result, .trial-history { border-top: 1px solid var(--cw-line); margin-top: 24px; padding-top: 18px; }
.trial-result header { margin-bottom: 12px; } .trial-result header span { font-size: 12px; color: var(--cw-text-muted); }
.trial-answer { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; padding: 16px; background: var(--cw-canvas); border-radius: 10px; margin-top: 14px; max-height: 420px; overflow-y: auto; }
.trial-history-row { display: block; text-align: left; width: 100%; color: var(--cw-text); background: transparent; border: 0; border-bottom: 1px solid var(--cw-line); padding: 14px 4px; cursor: pointer; }
.trial-history-row span, .trial-history-row small { display: block; overflow-wrap: anywhere; }
.trial-history-row small { color: var(--cw-text-muted); margin-top: 7px; }.trial-history-row:disabled { cursor: default; opacity: .55; }
.trial-history-row:focus-visible { outline: 2px solid var(--cw-focus-ring); outline-offset: 2px; }
@media (max-width: 480px) { dl { grid-template-columns: 64px minmax(0, 1fr); } .trial-scope { padding: 12px; } }
</style>
