<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { getRequestErrorMessage } from '@/api/request'
import CrudLoadState from '@/components/CrudLoadState.vue'
import {
  getKnowledgeGapReview,
  saveKnowledgeGapReview,
  type KnowledgeGap,
  type KnowledgeGapCategory,
  type KnowledgeGapPriority,
  type KnowledgeGapReview,
} from '@/api/ops'
import { GAP_CATEGORY_LABELS, GAP_CHANNEL_LABELS } from './knowledgeGapPresentation'

const props = defineProps<{ questionHash: string }>()
const emit = defineEmits<{ close: []; saved: [gap: KnowledgeGap] }>()
const auth = useAuthStore()
const canRead = computed(() => !!auth.token && auth.hasPermission('knowledge-gap:view'))
const canReview = computed(() => canRead.value && auth.hasPermission('improvement:manage'))
const loading = ref(false)
const submitting = ref(false)
const loadError = ref<unknown>(null)
const actionError = ref('')
const notice = ref('')
const noticeType = ref<'success' | 'info'>('success')
const needsReconciliation = ref(false)
const gap = ref<KnowledgeGap | null>(null)
const history = ref<KnowledgeGapReview[]>([])
const hasEarlier = ref(false)
const form = reactive<{
  category: KnowledgeGapCategory
  priority: KnowledgeGapPriority
  reason: string
}>({
  category: 'PENDING',
  priority: 'NORMAL',
  reason: '',
})
let initialized = false
let requestSequence = 0
const current = computed(() => gap.value?.classification)
const dirty = computed(
  () =>
    initialized &&
    (form.category !== (current.value?.category ?? 'PENDING') ||
      form.priority !== (current.value?.priority ?? 'NORMAL') ||
      form.reason.trim() !== (current.value?.origin === 'MANUAL' ? current.value.reason : '')),
)
const canSave = computed(
  () =>
    canReview.value &&
    !!gap.value &&
    !loading.value &&
    !submitting.value &&
    !loadError.value &&
    !needsReconciliation.value &&
    !!form.reason.trim() &&
    (dirty.value || current.value?.origin !== 'MANUAL'),
)

function setFormFromServer() {
  form.category = current.value?.category ?? 'PENDING'
  form.priority = current.value?.priority ?? 'NORMAL'
  // 规则不是人工复核理由，首次进入时要求操作人填写自己的判断。
  form.reason = current.value?.origin === 'MANUAL' ? current.value.reason : ''
  initialized = true
}

/** 首次读取初始化表单；重试及冲突核对保留人工输入，不自动重发写操作。 */
async function load(earlier = false) {
  if (!canRead.value || submitting.value || loading.value) return
  const sequence = ++requestSequence
  const source = props.questionHash
  const before = earlier ? history.value.at(-1)?.revision : undefined
  loading.value = true
  try {
    const result = await getKnowledgeGapReview(source, before)
    if (sequence !== requestSequence) return
    gap.value = result.gap
    history.value = earlier ? [...history.value, ...result.history] : result.history
    hasEarlier.value = result.history.length === 20
    if (!initialized) setFormFromServer()
    if (needsReconciliation.value) {
      noticeType.value = 'info'
      notice.value = '服务器记录已读取，当前输入已保留，请核对后再操作。'
    }
    needsReconciliation.value = false
    loadError.value = null
    actionError.value = ''
  } catch (error) {
    if (sequence === requestSequence) loadError.value = error
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function save() {
  if (!canSave.value) return
  const sequence = ++requestSequence
  const source = props.questionHash
  const payload = {
    ...form,
    reason: form.reason.trim(),
    expectedRevision: current.value?.revision ?? 0,
  }
  submitting.value = true
  notice.value = ''
  actionError.value = ''
  let saved = false
  try {
    const result = await saveKnowledgeGapReview(source, payload)
    if (sequence !== requestSequence) return
    gap.value = result
    setFormFromServer()
    noticeType.value = 'success'
    notice.value = '分类与复核记录已保存，可继续进入治理闭环处理。'
    emit('saved', result)
    saved = true
  } catch (error) {
    if (sequence !== requestSequence) return
    actionError.value = getRequestErrorMessage(error, '写入结果尚未核实')
    needsReconciliation.value = true
  } finally {
    if (sequence === requestSequence) submitting.value = false
  }
  if (saved) void load()
}

async function close(done: () => void) {
  if (submitting.value) return
  if (dirty.value && canReview.value) {
    try {
      await ElMessageBox.confirm('当前分类或理由尚未保存，关闭后将放弃这些输入。', '关闭问题详情', {
        confirmButtonText: '放弃修改并关闭',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  done()
  emit('close')
}

function time(ms?: number | null) {
  return ms ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '未记录'
}

watch(
  () => [props.questionHash, auth.token, canRead.value],
  () => {
    requestSequence += 1
    loading.value = false
    submitting.value = false
    gap.value = null
    history.value = []
    initialized = false
    form.category = 'PENDING'
    form.priority = 'NORMAL'
    form.reason = ''
    loadError.value = null
    actionError.value = ''
    notice.value = ''
    needsReconciliation.value = false
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onScopeDispose(() => {
  requestSequence += 1
})
</script>

<template>
  <el-drawer
    :model-value="true"
    title="问题详情与分类复核"
    size="min(720px, 100vw)"
    :before-close="close"
    :show-close="!submitting"
    :close-on-press-escape="!submitting"
    :close-on-click-modal="!submitting"
    class="gap-review-drawer"
  >
    <CrudLoadState :error="loadError" :has-stale-data="!!gap" :loading="loading" @retry="load()" />
    <div v-if="!gap && loading" class="loading" role="status">正在读取原始问题与复核记录…</div>
    <template v-if="gap && canRead">
      <blockquote class="source-question">
        <span>原始检索问题</span>
        <p>{{ gap.question }}</p>
      </blockquote>
      <div class="evidence-card">
        <div class="classification-line">
          <el-tag :type="current?.origin === 'MANUAL' ? 'success' : 'info'">{{
            GAP_CATEGORY_LABELS[current?.category ?? 'PENDING']
          }}</el-tag>
          <span>{{
            current?.origin === 'MANUAL'
              ? '人工复核'
              : current?.origin === 'RULE'
                ? '规则建议 · 待人工核对'
                : '原因尚未确认'
          }}</span>
          <el-tag v-if="current?.priority === 'HIGH'" type="danger">高优先级</el-tag>
        </div>
        <p class="current-reason">
          {{ current?.reason ?? '只有检索未命中证据，尚不足以判断原因' }}
        </p>
        <dl class="facts">
          <div>
            <dt>未命中次数</dt>
            <dd>{{ gap.missCount }} 次</dd>
          </div>
          <div>
            <dt>最近一次</dt>
            <dd>{{ time(gap.lastSeenAtMs) }}</dd>
          </div>
          <div>
            <dt>最近来源入口</dt>
            <dd>
              {{
                gap.evidence?.channelCode
                  ? (GAP_CHANNEL_LABELS[gap.evidence.channelCode] ?? gap.evidence.channelCode)
                  : '未记录'
              }}
            </dd>
          </div>
          <div>
            <dt>实际智能体</dt>
            <dd>{{ gap.evidence?.agentCode ?? '未记录' }}</dd>
          </div>
          <div>
            <dt>检索路径</dt>
            <dd>
              {{
                gap.evidence?.path === 'TOOL'
                  ? '工具检索'
                  : gap.evidence?.path === 'INJECTION'
                    ? '自动注入'
                    : '未记录'
              }}
            </dd>
          </div>
          <div>
            <dt>检索结果</dt>
            <dd>
              {{
                gap.evidence?.retrievalResult === 'EMPTY'
                  ? '正常返回，未召回资料'
                  : '历史记录未采集'
              }}
            </dd>
          </div>
        </dl>
        <p class="hint">
          来源仅描述最近一次样本；累计频次可能来自不同入口。未采集的意图与异常原因由人工核对。问题文本沿用最多
          500 字符的存储上限，长问题可能已截断。
        </p>
      </div>
      <el-alert
        v-if="!canReview"
        type="info"
        title="当前权限可查看来源与复核记录"
        :closable="false"
      />
      <section v-else class="review-editor">
        <h3>复核分类与优先级</h3>
        <p class="hint">
          低频问题也可提升优先级。分类用于分流处理，补知识与上线验证继续使用治理闭环。
        </p>
        <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" />
        <el-alert
          v-if="notice"
          :title="notice"
          :type="noticeType"
          :closable="false"
          role="status"
        />
        <div v-if="needsReconciliation" class="reconciliation" role="status">
          <span>写入结果尚未确认，输入已保留。先读取服务器记录，避免重复提交。</span>
          <el-button :loading="loading" @click="load()">核对服务器记录</el-button>
        </div>
        <el-form label-position="top" :disabled="submitting || loading">
          <div class="form-pair">
            <el-form-item label="处理分类"
              ><el-select v-model="form.category" aria-label="处理分类">
                <el-option
                  v-for="(label, value) in GAP_CATEGORY_LABELS"
                  :key="value"
                  :label="label"
                  :value="value"
                /> </el-select
            ></el-form-item>
            <el-form-item label="优先级"
              ><el-select v-model="form.priority" aria-label="优先级">
                <el-option label="普通" value="NORMAL" /><el-option
                  label="高优先级 · 严重单例"
                  value="HIGH"
                /> </el-select
            ></el-form-item>
          </div>
          <el-form-item label="复核理由" required
            ><el-input
              v-model="form.reason"
              type="textarea"
              :rows="4"
              maxlength="1000"
              show-word-limit
              aria-label="复核理由"
              placeholder="记录判断依据、需要核对的业务事实或处理方向"
          /></el-form-item>
          <div class="actions">
            <el-button type="primary" :loading="submitting" :disabled="!canSave" @click="save"
              >确认复核并保存</el-button
            >
            <span class="hint">{{
              current?.revision ? `当前为第 ${current.revision} 次复核` : '尚无人工复核记录'
            }}</span>
          </div>
        </el-form>
      </section>
      <section class="review-history">
        <div class="section-title">
          <h3>复核记录</h3>
          <el-button :disabled="submitting || loading" @click="load()">刷新记录</el-button>
        </div>
        <p v-if="!history.length && !loadError" class="hint">
          尚无人工复核。规则建议不会冒充人工结论。
        </p>
        <ol v-if="history.length" class="history-list">
          <li v-for="review in history" :key="review.revision">
            <div class="history-heading">
              <strong
                >{{ GAP_CATEGORY_LABELS[review.previousCategory] }} →
                {{ GAP_CATEGORY_LABELS[review.category] }}</strong
              >
              <el-tag v-if="review.priority === 'HIGH'" type="danger" size="small">高优先级</el-tag>
            </div>
            <p>{{ review.reason }}</p>
            <span class="hint"
              >第 {{ review.revision }} 次 · 用户 {{ review.reviewedBy }} ·
              {{ time(review.reviewedAtMs) }} · 当时 {{ review.signalCount }} 次未命中</span
            >
          </li>
        </ol>
        <el-button v-if="hasEarlier" :loading="loading" :disabled="submitting" @click="load(true)"
          >查看更早复核</el-button
        >
      </section>
    </template>
  </el-drawer>
</template>

<style scoped>
.source-question {
  margin: 0 0 18px;
  padding: 10px 14px;
  border-left: 3px solid var(--el-color-primary);
  background: var(--cw-paper);
  overflow-wrap: anywhere;
}
.source-question span,
.hint {
  color: var(--cw-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.source-question p {
  font-size: 18px;
  line-height: 1.65;
  margin: 6px 0 0;
}
.evidence-card {
  padding: 18px;
  border: 1px solid var(--cw-line);
  border-radius: var(--cw-radius-md);
  margin-bottom: 22px;
}
.classification-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
}
.current-reason {
  margin: 12px 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.facts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin: 16px 0;
}
.facts div {
  min-width: 0;
}
.facts dt {
  font-size: 12px;
  color: var(--cw-text-muted);
}
.facts dd {
  margin: 5px 0 0;
  overflow-wrap: anywhere;
  font-size: 14px;
}
.review-editor,
.review-history {
  margin-top: 24px;
}
h3 {
  font-size: 16px;
  margin: 0 0 12px;
}
.form-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.actions,
.section-title,
.history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}
.review-editor .el-alert,
.reconciliation {
  margin-bottom: 16px;
}
.reconciliation {
  display: grid;
  gap: 12px;
  color: var(--cw-text-muted);
  font-size: 14px;
}
.history-list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.history-list li {
  border-top: 1px solid var(--cw-line);
  padding: 16px 0;
}
.history-list p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  margin: 10px 0;
}
.history-heading strong {
  font-size: 14px;
}
@media (max-width: 640px) {
  .form-pair {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .facts {
    grid-template-columns: 1fr;
  }
  .review-editor :deep(.el-button),
  .review-history :deep(.el-button),
  .reconciliation :deep(.el-button) {
    min-height: 44px;
  }
  .source-question p {
    font-size: 16px;
  }
}
</style>
