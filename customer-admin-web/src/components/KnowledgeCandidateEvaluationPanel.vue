<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import { getImprovementCase, type ImprovementCase } from '@/api/improvement'
import { pageAgents } from '@/api/agent'
import { pageModels } from '@/api/model'
import { listDatasetVersions, type EvalDatasetRelease } from '@/api/eval'
import { bindKnowledgeCandidate, getKnowledgeCandidate, getKnowledgeCandidateReview, reevaluateKnowledgeCandidate,
  publishKnowledgeCandidate,
  type KnowledgeCandidate, type KnowledgeCandidateReview } from '@/api/knowledgeCandidate'
import type { AgentVO, ModelVO } from '@/types/api'
import CrudLoadState from './CrudLoadState.vue'

const props = defineProps<{ improvement: ImprovementCase; sourceKey: string; active: boolean; disabled: boolean }>()
const emit = defineEmits<{ result: [ImprovementCase | null]; busy: [boolean] }>()
const auth = useAuthStore()
const canRead = computed(() => props.active && !!auth.token && auth.hasPermission('knowledge-gap:view') && auth.hasPermission('eval:view'))
const canChoose = computed(() => canRead.value && auth.hasPermission('agent:view') && auth.hasPermission('model:view'))
const candidate = ref<KnowledgeCandidate | null>(null)
const review = ref<KnowledgeCandidateReview | null>(null)
const agents = ref<AgentVO[]>([])
const models = ref<ModelVO[]>([])
const releases = ref<EvalDatasetRelease[]>([])
const loading = ref(false)
const loaded = ref(false)
const submitting = ref(false)
const loadError = ref<unknown>(null)
const optionError = ref('')
const optionsLoading = ref(false)
const optionKeyword = ref('')
const optionTruncated = ref(false)
const actionError = ref('')
const notice = ref('')
const needsReconciliation = ref(false)
const publicationPreview = ref(false)
const remark = ref('')
const form = reactive({ agentId: undefined as number | undefined, modelDeploymentId: undefined as number | undefined,
  judgeDeploymentId: undefined as number | undefined, datasetReleaseId: '', targetCaseId: '' })
let generation = 0
let optionGeneration = 0
let initialized = false
const canAct = computed(() => canRead.value && loaded.value && !loading.value && !submitting.value
  && !props.disabled && !loadError.value && !needsReconciliation.value)
const bindable = computed(() => !['REEVALUATING', 'PUBLISHING', 'PUBLISHED', 'OBSERVING', 'VERIFIED', 'CANCELLED'].includes(props.improvement.status))
const selection = () => JSON.stringify([candidate.value?.id, candidate.value?.revision, form.agentId, form.modelDeploymentId,
  form.judgeDeploymentId, form.datasetReleaseId, form.targetCaseId.trim()])
const selectionChanged = computed(() => !review.value || selection() !== JSON.stringify([review.value.candidateId,
  review.value.candidateRevision, review.value.agentId, review.value.modelDeploymentId, review.value.judgeDeploymentId,
  review.value.datasetReleaseId, review.value.targetCaseId]))
const canBind = computed(() => canAct.value && canChoose.value && auth.hasPermission('knowledge-gap:fill')
  && bindable.value && candidate.value?.status === 'DRAFT' && !optionError.value && !optionsLoading.value
  && !!form.agentId && !!form.modelDeploymentId && !!form.judgeDeploymentId && !!form.datasetReleaseId && !!form.targetCaseId.trim())
const canRun = computed(() => canAct.value && !!review.value && auth.hasPermission('eval:run') && !selectionChanged.value
  && review.value.artifactFingerprint === props.improvement.artifactVersion
  && ['READY_FOR_REEVALUATION', 'REEVALUATION_FAILED'].includes(props.improvement.status))
const comparison = computed(() => review.value?.comparison)
const reevaluationDeadline = computed(() => props.improvement.reevaluationDeadlineAtMs
  ? new Date(props.improvement.reevaluationDeadlineAtMs).toLocaleString('zh-CN', { hour12: false }) : '')
const canPublish = computed(() => canAct.value && auth.hasPermission('knowledge-gap:fill')
  && props.improvement.status === 'READY_TO_PUBLISH' && props.improvement.reevaluationStatus === 'PASSED'
  && candidate.value?.status === 'DRAFT' && !selectionChanged.value && !!comparison.value
  && comparison.value.current.runId === props.improvement.evalRunId
  && review.value?.artifactFingerprint === props.improvement.artifactVersion)
const selectedRelease = computed(() => releases.value.find(value => value.releaseId === form.datasetReleaseId))

function adoptSelection() {
  form.agentId = review.value?.agentId ?? props.improvement.agentId ?? undefined
  form.modelDeploymentId = review.value?.modelDeploymentId
  form.judgeDeploymentId = review.value?.judgeDeploymentId
  form.datasetReleaseId = review.value?.datasetReleaseId ?? ''
  form.targetCaseId = review.value?.targetCaseId ?? props.improvement.evalCaseId ?? ''
  initialized = true
}

/** 核对父记录、当前草稿和绑定证据后才恢复操作，读取失败不覆盖用户选择。 */
async function load() {
  if (!canRead.value || loading.value || submitting.value) return
  const requestId = ++generation
  loading.value = true
  try {
    const [draft, current] = await Promise.all([getKnowledgeCandidate(props.sourceKey), getImprovementCase('KNOWLEDGE_GAP', props.sourceKey)])
    if (requestId !== generation) return
    const evidence = current ? await getKnowledgeCandidateReview(current.id) : null
    if (requestId !== generation) return
    candidate.value = draft
    review.value = evidence
    emit('result', current)
    if (!initialized) adoptSelection()
    loaded.value = true
    loadError.value = null
    actionError.value = ''
    needsReconciliation.value = false
  } catch (error) {
    if (requestId === generation) loadError.value = error
  } finally {
    if (requestId === generation) loading.value = false
  }
}

async function loadOptions() {
  if (!canChoose.value || optionsLoading.value) return
  const requestId = ++optionGeneration
  optionsLoading.value = true
  try {
    const [agentPage, modelPage, versions] = await Promise.all([
      pageAgents({ pageNum: 1, pageSize: 100, status: 1, keyword: optionKeyword.value.trim() || undefined }),
      pageModels({ pageNum: 1, pageSize: 100, status: 1, keyword: optionKeyword.value.trim() || undefined }), listDatasetVersions('QUALITY'),
    ])
    if (requestId !== optionGeneration) return
    agents.value = agentPage.list.filter(value => value.status === 1)
    models.value = modelPage.list.filter(value => value.status === 1)
    releases.value = versions.filter(value => value.status === 'APPROVED')
    optionTruncated.value = agentPage.total > 100 || modelPage.total > 100
    optionError.value = ''
  } catch (error) {
    if (requestId === optionGeneration) optionError.value = getRequestErrorMessage(error, '评测配置暂不可用')
  } finally {
    if (requestId === optionGeneration) optionsLoading.value = false
  }
}

async function submit(action: () => Promise<ImprovementCase>, message: string) {
  if (!canAct.value) return
  const requestId = generation
  submitting.value = true
  emit('busy', true)
  notice.value = ''; actionError.value = ''
  try {
    const result = await action()
    if (requestId !== generation) return
    emit('result', result)
    notice.value = result.reevaluationStatus === 'FAILED' ? '复评未通过，请查看失败原因和逐题对照。' : message
    submitting.value = false
    emit('busy', false)
    await load()
  } catch (error) {
    if (requestId !== generation) return
    actionError.value = getRequestErrorMessage(error, '操作结果尚未确认，请核对服务器记录')
    needsReconciliation.value = true
  } finally {
    if (requestId === generation) { submitting.value = false; emit('busy', false) }
  }
}

function bind() {
  if (!canBind.value || !candidate.value) return
  const data = { candidateId: candidate.value.id, candidateRevision: candidate.value.revision, agentId: form.agentId!,
    modelDeploymentId: form.modelDeploymentId!, judgeDeploymentId: form.judgeDeploymentId!,
    datasetReleaseId: form.datasetReleaseId, targetCaseId: form.targetCaseId.trim() }
  return submit(() => bindKnowledgeCandidate(props.improvement.id, data), '候选与评测输入已冻结，可以运行对照复评。')
}

function run() {
  if (!canRun.value) return
  return submit(() => reevaluateKnowledgeCandidate(props.improvement.id, remark.value.trim() || undefined), '对照复评已完成。')
}

function publish() {
  if (!canPublish.value || !publicationPreview.value || !review.value || !comparison.value) return
  const id = props.improvement.id
  const data = { expectedArtifactFingerprint: review.value.artifactFingerprint,
    expectedEvaluationRunId: comparison.value.current.runId }
  publicationPreview.value = false
  return submit(() => publishKnowledgeCandidate(id, data), '发布任务已提交，正在核对正式知识写入结果。')
}

// 页面停留期间回读执行事实；不自动重发模型调用，也不因旧请求迟到而恢复已关闭的面板。
let progressPoll: ReturnType<typeof setTimeout> | undefined
let disposed = false
function pollProgress() {
  clearTimeout(progressPoll)
  if (disposed || !canRead.value || !['PUBLISHING', 'REEVALUATING'].includes(props.improvement.status)) return
  progressPoll = setTimeout(async () => { await load(); pollProgress() }, 5000)
}
watch([() => props.improvement.status, () => canRead.value], pollProgress, { immediate: true })
watch(selection, () => { publicationPreview.value = false })

watch(() => props.improvement.evalCaseId, value => {
  if (!form.targetCaseId && value) form.targetCaseId = value
})

// 分别比较标量身份，父记录回读产生的新对象不能重置当前输入或触发循环读取。
watch([() => props.sourceKey, () => props.improvement.id, () => props.active,
  () => auth.loginGeneration, () => canRead.value, () => canChoose.value], () => {
  generation += 1; optionGeneration += 1; initialized = false
  loaded.value = false; loading.value = false; submitting.value = false; optionsLoading.value = false
  candidate.value = null; review.value = null; agents.value = []; models.value = []; releases.value = []
  loadError.value = null; optionError.value = ''; actionError.value = ''; notice.value = ''; needsReconciliation.value = false
  optionKeyword.value = ''; optionTruncated.value = false
  publicationPreview.value = false
  remark.value = ''; Object.assign(form, { agentId: undefined, modelDeploymentId: undefined, judgeDeploymentId: undefined, datasetReleaseId: '', targetCaseId: '' })
  emit('busy', false)
  void load(); void loadOptions()
}, { immediate: true, flush: 'sync' })
onScopeDispose(() => { disposed = true; generation += 1; optionGeneration += 1; clearTimeout(progressPoll); emit('busy', false) })
</script>

<template>
  <section class="knowledge-evaluation" aria-label="知识候选对照复评">
    <h3>知识候选对照复评</h3>
    <p class="scope-note">同一提示词、模型和审核用例，分别试用补充知识前后的答复。本次只运行知识查询，不办理订单或其它写入业务。</p>
    <el-alert v-if="!canRead" type="info" :closable="false" title="需要知识读取与评测读取权限才能查看候选评测。" />
    <template v-else>
      <CrudLoadState :loading="loading" :error="loadError" :has-stale-data="loaded" @retry="load" />
      <el-alert v-if="actionError" type="error" :closable="false" :title="actionError" description="选择和备注已保留，请先核对服务器记录。" />
      <el-alert v-if="notice" type="info" :closable="false" :title="notice" />
      <el-alert v-if="loaded && !candidate" type="warning" :closable="false" title="请先在知识候选中保存正文，再绑定评测。" />
      <template v-if="candidate">
        <div class="candidate-summary"><strong>{{ candidate.title }}</strong><el-tag>当前候选 v{{ candidate.revision }}</el-tag></div>
        <el-alert v-if="review && selectionChanged" type="warning" :closable="false" title="候选或评测选择已变化，需要重新冻结后复评。" />
        <p v-if="!canChoose" class="scope-note">需要智能体和模型读取权限才能选择评测配置。已有评测仍可查看。</p>
        <el-form v-if="canChoose && bindable" label-position="top" :disabled="!canAct || !auth.hasPermission('knowledge-gap:fill')" class="binding-form">
          <div class="panel-actions full-row configuration-search"><el-input v-model="optionKeyword" placeholder="按名称查找智能体或模型" aria-label="查找评测配置" clearable /><el-button :loading="optionsLoading" @click="loadOptions">查找配置</el-button></div>
          <p v-if="optionTruncated" class="scope-note full-row">当前展示前 100 条匹配配置，可输入更具体的名称查找。</p>
          <el-form-item label="智能体提示词">
            <el-select v-model="form.agentId" filterable placeholder="选择已启用的智能体" aria-label="智能体提示词">
              <el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.agentName" />
            </el-select>
          </el-form-item>
          <el-form-item label="答复模型">
            <el-select v-model="form.modelDeploymentId" filterable placeholder="选择答复模型部署" aria-label="答复模型">
              <el-option v-for="model in models" :key="model.id" :value="model.id" :label="`${model.modelName} · ${model.model}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="评分模型">
            <el-select v-model="form.judgeDeploymentId" filterable placeholder="选择评分模型部署" aria-label="评分模型">
              <el-option v-for="model in models" :key="model.id" :value="model.id" :label="`${model.modelName} · ${model.model}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="已审核的质量用例集">
            <el-select v-model="form.datasetReleaseId" filterable placeholder="选择审核通过的版本" aria-label="已审核的质量用例集">
              <el-option v-for="release in releases" :key="release.releaseId" :value="release.releaseId" :label="`${release.versionName} · ${release.caseCount} 题`" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标回归用例编号" class="full-row"><el-input v-model="form.targetCaseId" maxlength="128" placeholder="目标用例须包含在所选审核版本中" /></el-form-item>
          <p class="scope-note full-row">先创建目标回归用例，再在评测数据集中创建并审核包含该用例的版本。{{ selectedRelease ? `本次两组各评测 ${selectedRelease.caseCount} 题，会产生模型调用费用。` : '' }}</p>
          <el-alert v-if="optionError" class="full-row" type="error" :closable="false" :title="optionError" />
          <div class="panel-actions full-row"><el-button :loading="optionsLoading" @click="loadOptions">刷新可选配置</el-button><el-button type="primary" :disabled="!canBind" :loading="submitting" @click="bind">冻结知识候选</el-button></div>
        </el-form>
      </template>
      <div v-if="review" class="bound-summary">
        <strong>已绑定候选 v{{ review.candidateRevision }} · {{ review.cases.length }} 题</strong>
        <p>答复：{{ review.modelName }}　评分：{{ review.judgeModelName }}　目标：{{ review.targetCaseId }}</p>
      </div>
      <div class="panel-actions">
        <el-input v-model="remark" :disabled="!canAct" placeholder="本次复评说明" aria-label="本次复评说明" maxlength="500" />
        <el-button v-if="auth.hasPermission('eval:run')" type="primary" :disabled="!canRun" :loading="submitting" @click="run">运行知识对照复评</el-button>
        <el-button :disabled="loading || submitting" @click="load">核对评测记录</el-button>
      </div>
      <p v-if="props.improvement.status === 'REEVALUATING'" role="status" class="scope-note">复评正在执行，页面会自动核对记录。请勿重复发起。<span v-if="reevaluationDeadline">本次时限至 {{ reevaluationDeadline }}，超时后可核对并重新发起。</span></p>
      <section v-if="review" class="publication" aria-label="知识候选发布">
        <el-alert v-if="props.improvement.status === 'PUBLISHED'" type="success" :closable="false" title="知识已发布"
          :description="`正式知识编号：${props.improvement.publishRevision ?? '待核对'}。候选正文已写入正式知识；线上效果尚未验证。`" />
        <el-alert v-else-if="props.improvement.status === 'PUBLISHING'" type="info" :closable="false" title="发布结果核对中"
          description="页面会自动更新。可以离开，稍后回来查看同一任务的实际结果。" />
        <el-alert v-else-if="props.improvement.status === 'PUBLISH_FAILED'" type="error" :closable="false" title="知识未发布"
          :description="`${props.improvement.lastError ?? '发布前提未满足'}。请核对候选，重新冻结并复评。`" />
        <div class="panel-actions">
          <el-button v-if="auth.hasPermission('knowledge-gap:fill') && !['PUBLISHING', 'PUBLISHED'].includes(props.improvement.status)"
            type="primary" :disabled="!canPublish" @click="publicationPreview = true">审阅并发布知识</el-button>
          <el-button :disabled="loading || submitting" @click="load">核对发布状态</el-button>
        </div>
        <div v-if="publicationPreview && candidate" class="publication-preview" aria-label="待发布知识正文">
          <h4>将发布候选 v{{ candidate.revision }}：{{ candidate.title }}</h4>
          <p>{{ candidate.content }}</p>
          <p class="scope-note">确认后加入正式知识。系统会再次检查候选版本、人工复核和本次评测证据。</p>
          <div class="panel-actions"><el-button :disabled="submitting" @click="publicationPreview = false">返回核对</el-button>
            <el-button type="primary" :disabled="!canPublish" :loading="submitting" @click="publish">确认发布知识</el-button></div>
        </div>
      </section>
      <section v-if="comparison && review" aria-label="逐题答复对照" class="comparison">
        <div class="score-pair"><span>补充前 <strong>{{ ((comparison.baseline?.primaryMetric ?? 0) * 5).toFixed(2) }}</strong> / 5</span><span>补充后 <strong>{{ (comparison.current.primaryMetric * 5).toFixed(2) }}</strong> / 5</span></div>
        <p class="scope-note">修复 {{ comparison.fixes.length }} 题 · 新增回归 {{ comparison.regressions.length }} 题。通过结论还要求目标实际召回候选、评分完整且整体质量不下降。</p>
        <details v-for="item in review.cases" :key="item.caseId" class="case-comparison" :open="item.caseId === review.targetCaseId">
          <summary>{{ item.caseId === review.targetCaseId ? '目标问题 · ' : '' }}{{ item.input }}</summary>
          <p class="expected">期望要点：{{ item.expected }}</p>
          <div class="reply-pair"><div><h4>补充前 <el-tag :type="item.baselineFailed ? 'danger' : 'success'" size="small">{{ item.baselineFailed ? '未通过' : '通过' }}</el-tag></h4><p>{{ item.baselineReply }}</p></div><div><h4>补充后 <el-tag :type="item.candidateFailed ? 'danger' : 'success'" size="small">{{ item.candidateFailed ? '未通过' : '通过' }}</el-tag></h4><p>{{ item.candidateReply }}</p><small>{{ item.candidateRecalled ? '已实际检索到候选' : '未检索到候选' }}</small></div></div>
        </details>
      </section>
    </template>
  </section>
</template>

<style scoped>
.knowledge-evaluation { display: grid; gap: 14px; margin-top: 20px; min-width: 0; }
h3, h4, p { margin: 0; }
h3 { font-size: 16px; color: var(--cw-text); }
.scope-note, .bound-summary p, .expected, small { font-size: 12px; line-height: 1.7; color: var(--cw-text-muted); overflow-wrap: anywhere; }
.candidate-summary, .panel-actions, .score-pair { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.candidate-summary strong { overflow-wrap: anywhere; }
.binding-form, .reply-pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.full-row { grid-column: 1 / -1; }
.binding-form .el-select { width: 100%; }
.binding-form .scope-note { margin-bottom: 14px; }
.configuration-search { margin-bottom: 16px; }
.panel-actions .el-input { flex: 1 1 180px; }
.panel-actions .el-button { margin-left: 0; }
.bound-summary, .case-comparison { border: 1px solid var(--cw-line); background: var(--cw-paper); border-radius: var(--cw-radius-md); padding: 14px; }
.bound-summary { border-left: 3px solid var(--el-color-primary); }
.comparison { display: grid; gap: 12px; }
.publication, .publication-preview { display: grid; gap: 12px; min-width: 0; }
.publication-preview { border: 1px solid var(--cw-line); border-radius: var(--cw-radius-md); padding: 14px; background: var(--cw-paper); }
.publication-preview p, .publication-preview h4 { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; }
.score-pair { padding: 14px 0; justify-content: space-around; }
.score-pair strong { color: var(--el-color-primary); font-size: 24px; font-variant-numeric: tabular-nums; }
.case-comparison summary { cursor: pointer; line-height: 1.6; font-weight: 600; overflow-wrap: anywhere; }
.expected { margin: 12px 0; }
.reply-pair > div { padding: 12px; min-width: 0; border-radius: 6px; background: var(--cw-canvas); }
.reply-pair h4 { display: flex; align-items: center; gap: 8px; font-size: 13px; margin-bottom: 10px; }
.reply-pair p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; }
@media (max-width: 640px) { .binding-form, .reply-pair { grid-template-columns: minmax(0, 1fr); gap: 12px; } .panel-actions .el-button { min-height: 44px; } }
</style>
