<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import { getKnowledgeGapReview, type KnowledgeGap } from '@/api/ops'
import { getKnowledgeCandidate, saveKnowledgeCandidate, KNOWLEDGE_CANDIDATE_LIMITS as limits, type KnowledgeCandidate, type KnowledgeCandidateSaveRequest } from '@/api/knowledgeCandidate'
import { generateUuid } from '@/utils/uuid'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { GAP_CATEGORY_LABELS } from './knowledgeGapPresentation'

const props = defineProps<{ questionHash: string }>()
const emit = defineEmits<{ close: []; governance: [] }>()
const auth = useAuthStore()
const canRead = computed(() => !!auth.token && auth.hasPermission('knowledge-gap:view'))
const canWrite = computed(() => canRead.value && auth.hasPermission('knowledge-gap:fill'))
const gap = ref<KnowledgeGap | null>(null)
const saved = ref<KnowledgeCandidate | null>(null)
const candidateId = ref(generateUuid())
const baseRevision = ref(0)
const initialized = ref(false)
const loading = ref(false)
const submitting = ref(false)
const loadError = ref<unknown>(null)
const actionError = ref('')
const notice = ref('')
const needsReconciliation = ref(false)
const versionConflict = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ title: '', content: '', keyword: '' })
const baseline = ref('')
let sequence = 0
let pending: { id: string; data: KnowledgeCandidateSaveRequest } | null = null
const fingerprint = () => JSON.stringify([form.title.trim(), form.content.trim(), form.keyword.trim()])
const dirty = computed(() => initialized.value && fingerprint() !== baseline.value)
const classification = computed(() => gap.value?.classification)
const confirmedKnowledge = computed(() => classification.value?.origin === 'MANUAL' && classification.value.category === 'KNOWLEDGE')
const canSave = computed(() => canWrite.value && initialized.value && confirmedKnowledge.value
  && !loading.value && !submitting.value && !loadError.value && !needsReconciliation.value && !versionConflict.value
  && (!saved.value || saved.value.status === 'DRAFT')
  && (dirty.value || baseRevision.value === 0 || saved.value?.sourceReviewRevision !== classification.value?.revision))
const canOpenGovernance = computed(() => !!saved.value && confirmedKnowledge.value
  && saved.value.sourceReviewRevision === classification.value?.revision
  && !dirty.value && !loading.value && !loadError.value && !submitting.value
  && !versionConflict.value && !needsReconciliation.value)
const rules: FormRules = {
  title: [{ required: true, whitespace: true, message: '请填写候选标题', trigger: 'blur' },
    { max: limits.title, message: '候选标题最多 200 字', trigger: 'blur' }],
  content: [{ required: true, whitespace: true, message: '请填写候选正文', trigger: 'blur' },
    { max: limits.content, message: '候选正文最多 20,000 字', trigger: 'blur' }],
  keyword: [{ required: true, whitespace: true, message: '请填写检索关键词', trigger: 'blur' },
    { max: limits.keyword, message: '检索关键词最多 255 字，请精简后保存', trigger: 'blur' }],
}

function adoptServer(keepInput = false) {
  candidateId.value = saved.value?.id ?? candidateId.value
  baseRevision.value = saved.value?.revision ?? 0
  if (!keepInput) {
    form.title = saved.value?.title ?? ''
    form.content = saved.value?.content ?? ''
    form.keyword = saved.value?.keyword ?? gap.value?.question ?? ''
  }
  baseline.value = JSON.stringify([saved.value?.title ?? '', saved.value?.content ?? '', saved.value?.keyword ?? gap.value?.question ?? ''].map(value => value.trim()))
  initialized.value = true
  versionConflict.value = false
  actionError.value = ''
  pending = null
}

function matchesSubmission(value: KnowledgeCandidate | null, submission: NonNullable<typeof pending>) {
  return !!value && value.id === submission.id && value.questionHash === submission.data.questionHash
    && value.revision === submission.data.expectedRevision + 1
    && value.sourceReviewRevision === submission.data.sourceReviewRevision
    && value.title === submission.data.title && value.content === submission.data.content && value.keyword === submission.data.keyword
}

/** 读取失败保留草稿；版本冲突须由用户看过服务器内容后决定如何继续。 */
async function load() {
  if (!canRead.value || loading.value || submitting.value) return
  const current = ++sequence
  const source = props.questionHash
  loading.value = true
  try {
    const [review, candidateResult] = await Promise.allSettled([getKnowledgeGapReview(source), getKnowledgeCandidate(source)])
    if (current !== sequence) return
    // 来源服务故障不隐藏已保存正文，但当前分类未经核对时禁止修改与后续提交。
    gap.value = review.status === 'fulfilled' ? review.value.gap : null
    if (candidateResult.status === 'rejected') throw candidateResult.reason
    const candidate = candidateResult.value
    saved.value = candidate
    if (!initialized.value) {
      if (candidate || gap.value) adoptServer()
    }
    else if (pending && matchesSubmission(candidate, pending)) {
      adoptServer(true)
      notice.value = '已核对到保存结果，当前输入继续保留。'
    } else {
      versionConflict.value = (candidate?.revision ?? 0) !== baseRevision.value
        || (!!candidate && candidate.id !== candidateId.value)
      notice.value = versionConflict.value ? '服务器已有不同版本，请对照后选择如何继续。' : '服务器版本已核对，当前输入已保留，可继续编辑。'
      pending = null
    }
    needsReconciliation.value = false
    loadError.value = review.status === 'rejected' ? review.reason : null
    actionError.value = ''
  } catch (error) {
    if (current === sequence) loadError.value = error
  } finally {
    if (current === sequence) loading.value = false
  }
}

async function save() {
  if (!canSave.value) return
  const current = sequence
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || current !== sequence || !canSave.value) return
  const request = {
    id: candidateId.value,
    data: { questionHash: props.questionHash, expectedRevision: baseRevision.value,
      sourceReviewRevision: classification.value!.revision, title: form.title.trim(),
      content: form.content.trim(), keyword: form.keyword.trim() },
  }
  pending = request
  submitting.value = true
  notice.value = ''
  actionError.value = ''
  try {
    const result = await saveKnowledgeCandidate(request.id, request.data)
    if (current !== sequence) return
    if (!matchesSubmission(result, request)) throw new Error('返回的候选版本与本次提交不一致，请先核对保存结果')
    saved.value = result
    adoptServer(true)
    notice.value = `候选 v${result.revision} 已保存，可继续进入治理闭环。`
  } catch (error) {
    if (current !== sequence) return
    actionError.value = getRequestErrorMessage(error, '保存结果尚未确认，输入已保留，请先核对已保存版本')
    needsReconciliation.value = true
  } finally {
    if (current === sequence) submitting.value = false
  }
}

async function close() {
  if (submitting.value) return
  if (dirty.value) {
    try { await ElMessageBox.confirm('当前编辑尚未保存，关闭后将丢弃这些编辑。', '关闭知识候选', { confirmButtonText: '丢弃并关闭', cancelButtonText: '继续编辑', type: 'warning' }) }
    catch { return }
  }
  sequence += 1
  emit('close')
}

watch(() => [props.questionHash, auth.loginGeneration, canRead.value], () => {
  sequence += 1
  gap.value = null
  saved.value = null
  initialized.value = false
  pending = null
  loading.value = false
  submitting.value = false
  if (!canRead.value) { emit('close'); return }
  candidateId.value = generateUuid()
  baseRevision.value = 0
  form.title = ''; form.content = ''; form.keyword = ''
  needsReconciliation.value = false
  versionConflict.value = false
  notice.value = ''; actionError.value = ''; loadError.value = null
  void load()
}, { immediate: true, flush: 'sync' })
onScopeDispose(() => { sequence += 1 })
</script>

<template>
  <el-drawer :model-value="true" title="知识候选" size="min(960px, 100vw)" class="knowledge-candidate-drawer"
    :before-close="close" :close-on-click-modal="false" :show-close="!submitting" :close-on-press-escape="!submitting">
    <CrudLoadState :loading="loading" :error="loadError" :has-stale-data="initialized" @retry="load" />
    <div v-if="gap || initialized" class="candidate-layout">
      <aside class="candidate-source" aria-label="候选的原始依据">
        <span class="section-label">原始问题</span>
        <blockquote v-if="gap">{{ gap.question }}</blockquote>
        <p v-if="gap">{{ gap.missCount }} 次未命中 · {{ GAP_CATEGORY_LABELS[classification?.category ?? 'PENDING'] }}</p>
        <el-alert v-else type="warning" :closable="false" title="原始问题暂时不可读"
          description="已保存候选仍可查看，来源恢复并核对后才能继续修改。" />
        <el-tag v-if="gap" :type="confirmedKnowledge ? 'success' : 'warning'">
          {{ classification?.origin === 'MANUAL' ? `人工复核 v${classification.revision}` : '尚未人工复核' }}
        </el-tag>
        <p class="review-reason">{{ classification?.reason }}</p>
        <el-alert v-if="gap && !confirmedKnowledge" type="warning" :closable="false" title="先复核问题分类"
          description="在问题详情中确认这是知识缺口后，再编辑和保存候选。实时查询、流程与依赖问题应按相应方式处理。" />
        <p class="workflow-note">候选保存后，进入治理闭环认领责任、绑定回归用例，再核对评测与发布结果。</p>
      </aside>
      <section class="candidate-editor" aria-label="编辑知识候选">
        <div class="editor-heading"><h2>候选正文</h2><el-tag type="info">{{ baseRevision ? `基于 v${baseRevision}` : '尚未保存' }}</el-tag></div>
        <el-alert v-if="notice" :title="notice" type="info" :closable="false" show-icon />
        <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
        <p v-if="!canWrite" class="candidate-hint">当前为只读访问，需要补知识权限才能保存。</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="!canWrite || submitting || !confirmedKnowledge || !initialized">
          <el-form-item label="候选标题" prop="title"><el-input v-model="form.title" :maxlength="limits.title" show-word-limit placeholder="如：货到付款支持范围" /></el-form-item>
          <el-form-item label="候选正文" prop="content"><el-input v-model="form.content" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" :maxlength="limits.content" show-word-limit placeholder="写清适用范围、处理规则和需要人工确认的例外" /></el-form-item>
          <el-form-item label="检索关键词" prop="keyword"><el-input v-model="form.keyword" :maxlength="limits.keyword" show-word-limit placeholder="用逗号分隔关键词" /></el-form-item>
        </el-form>
        <section v-if="versionConflict" class="server-version" aria-label="服务器候选版本">
          <h3>服务器候选 v{{ saved?.revision ?? 0 }}</h3>
          <strong>{{ saved?.title }}</strong><pre>{{ saved?.content }}</pre><p>{{ saved?.keyword }}</p>
          <div class="conflict-actions">
            <el-button @click="adoptServer()">载入服务器内容</el-button>
            <el-button v-if="canWrite" @click="adoptServer(true)">基于此版本保留我的编辑</el-button>
          </div>
        </section>
      </section>
    </div>
    <template #footer>
      <div class="candidate-footer">
        <span>每次保存保留独立修订，评测和发布按具体版本核对。</span>
        <div class="footer-actions">
          <el-button :disabled="submitting || loading" @click="load">核对已保存版本</el-button>
          <el-button v-if="auth.hasPermission('improvement:manage')" :disabled="!canOpenGovernance" @click="emit('governance')">进入治理闭环</el-button>
          <el-button class="cw-final-action" type="primary" :disabled="!canSave" :loading="submitting" @click="save">保存候选</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.candidate-layout { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(0, 2fr); gap: 28px; }
.candidate-source { border-left: 3px solid var(--cw-cobalt); padding: 0 16px; align-self: start; overflow-wrap: anywhere; }
.section-label { color: var(--cw-text-muted); font-size: 12px; font-weight: 600; }
.candidate-source blockquote { margin: 12px 0; color: var(--cw-text); font-size: 17px; line-height: 1.65; }
.candidate-source p { color: var(--cw-text-muted); font-size: 12px; line-height: 1.7; }
.review-reason { white-space: pre-wrap; }
.workflow-note { border-top: 1px solid var(--cw-line); padding-top: 14px; margin-top: 22px; }
.candidate-editor { min-width: 0; }
.editor-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 18px; }
.editor-heading h2 { font-size: 17px; margin: 0; color: var(--cw-text); }
.candidate-editor .el-alert { margin-bottom: 14px; }
.candidate-hint { color: var(--cw-text-muted); }
.server-version { padding: 16px; border: 1px solid var(--cw-line); border-radius: 8px; background: var(--cw-canvas); overflow-wrap: anywhere; }
.server-version h3 { margin: 0 0 12px; font-size: 14px; }
.server-version pre { font-family: inherit; white-space: pre-wrap; max-height: 240px; overflow: auto; line-height: 1.7; }
.conflict-actions, .footer-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.conflict-actions .el-button, .footer-actions .el-button { margin-left: 0; }
.candidate-footer { display: flex; flex-direction: column; gap: 12px; align-items: flex-end; }
.candidate-footer > span { color: var(--cw-text-muted); font-size: 12px; }
@media (max-width: 640px) {
  .candidate-layout { grid-template-columns: minmax(0, 1fr); gap: 22px; }
  .candidate-source { padding-right: 0; }
  .candidate-source blockquote { font-size: 15px; margin: 8px 0; }
  .candidate-footer { align-items: stretch; }
  .footer-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .footer-actions .cw-final-action { grid-column: 1 / -1; }
}
</style>
