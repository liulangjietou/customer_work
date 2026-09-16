<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { getChatKnowledgeSources, previewChatKnowledgeSource } from '@/api/chat'
import { useAuthStore } from '@/store/auth'
import type {
  ChatKnowledgeRetrievalVO,
  ChatKnowledgeSourcesVO,
  ChatKnowledgeSourceVO,
  KnowledgeDocumentPreviewVO,
} from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  agentCode: string
  sessionId: string
  messageId: string | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  closed: []
  recorded: [messageId: string]
}>()
const auth = useAuthStore()
const canRead = computed(() => !!auth.token && auth.hasPermission('workspace'))
const result = ref<ChatKnowledgeSourcesVO | null>(null)
const listLoading = ref(false)
const listError = ref<unknown>(null)
const selected = ref<ChatKnowledgeSourceVO | null>(null)
const preview = ref<KnowledgeDocumentPreviewVO | null>(null)
const previewLoading = ref(false)
const previewError = ref<unknown>(null)
const previewHeading = ref<HTMLElement>()
let generation = 0
let listSequence = 0
let previewSequence = 0
let sourceButton: HTMLButtonElement | null = null

function errorCode(error: unknown): number | undefined {
  const value = error as { code?: number; response?: { data?: { code?: number } } } | null
  return value?.response?.data?.code ?? value?.code
}
function failure(error: unknown) {
  if (errorCode(error) === 20001) return '当前账号无权预览此来源，请联系管理员核对权限。'
  if (errorCode(error) === 30003) return '此检索来源已不可用，文档可能已撤回或清理。'
  return '暂时无法读取检索参考，请检查连接后重试。'
}
const retryPreview = computed(() => ![20001, 30003].includes(errorCode(previewError.value) ?? 0))
function retrievalLabel(retrieval: ChatKnowledgeRetrievalVO) {
  return {
    HIT: '本轮检索命中',
    MISS: '本轮检索未命中',
    SKIPPED: '本轮跳过知识检索',
    DEGRADED: '本轮检索未完整完成',
  }[retrieval.status]
}
function sourceLabel(row: ChatKnowledgeSourceVO) {
  if (row.status === 'FORBIDDEN') return '当前账号无权预览'
  if (row.status === 'UNAVAILABLE') return '来源已失效'
  if (row.status === 'EXTERNAL') return '外部来源仅提供线索，无法预览原文'
  return row.document?.sourceName || '知识文档'
}
function isCurrent(key: number) {
  return key === generation && props.modelValue && canRead.value
}

/** 抽屉与权限上下文变化时同步清除原文，迟到响应不能恢复旧内容。 */
function invalidate() {
  generation += 1
  listSequence += 1
  previewSequence += 1
  result.value = null
  selected.value = null
  preview.value = null
  listLoading.value = false
  previewLoading.value = false
  listError.value = null
  previewError.value = null
  sourceButton = null
}
const context = [
  () => props.modelValue,
  () => props.agentCode,
  () => props.sessionId,
  () => props.messageId,
  () => auth.token,
  () => auth.username,
  () => auth.permissions,
]
watch(context, invalidate, { flush: 'sync', deep: true })
watch(
  context,
  () => {
    if (props.modelValue && canRead.value) void loadSources()
  },
  { immediate: true, flush: 'post', deep: true },
)
onBeforeUnmount(invalidate)

/** GET 是来源留存的核对依据，不从完成阶段或 SSE 收尾推导。 */
async function loadSources() {
  if (!canRead.value || !props.messageId) return
  const key = generation
  const sequence = ++listSequence
  previewSequence += 1
  selected.value = null
  preview.value = null
  previewLoading.value = false
  previewError.value = null
  result.value = null
  listLoading.value = true
  listError.value = null
  try {
    const data = await getChatKnowledgeSources(props.agentCode, props.sessionId, props.messageId)
    if (!isCurrent(key) || sequence !== listSequence) return
    if (!['RECORDED', 'NOT_RECORDED'].includes(data.status) || !Array.isArray(data.retrievals))
      throw new Error('Invalid knowledge source response')
    result.value = data
    if (data.status === 'RECORDED') emit('recorded', props.messageId)
  } catch (error) {
    if (isCurrent(key) && sequence === listSequence) listError.value = error
  } finally {
    if (isCurrent(key) && sequence === listSequence) listLoading.value = false
  }
}

async function readSource(row: ChatKnowledgeSourceVO, event?: Event) {
  if (!canRead.value || !props.messageId || row.status !== 'AVAILABLE' || !row.document) return
  const key = generation
  const sequence = ++previewSequence
  const document = row.document
  const { agentCode, sessionId, messageId } = props
  selected.value = row
  preview.value = null
  previewError.value = null
  previewLoading.value = true
  if (event?.currentTarget instanceof HTMLButtonElement) sourceButton = event.currentTarget
  await nextTick()
  if (!isCurrent(key) || sequence !== previewSequence) return
  previewHeading.value?.focus()
  try {
    const data = await previewChatKnowledgeSource(agentCode, sessionId, messageId, row.sourceId)
    if (!isCurrent(key) || sequence !== previewSequence) return
    if (
      data.document?.knowledgeBaseId !== document.knowledgeBaseId ||
      data.document.versionId !== document.versionId ||
      data.document.revisionId !== document.revisionId ||
      data.document.status !== 'AVAILABLE' ||
      typeof data.content !== 'string'
    )
      throw new Error('Knowledge source document does not match the selected reference')
    preview.value = data
  } catch (error) {
    if (!isCurrent(key) || sequence !== previewSequence) return
    previewError.value = error
    if ([20001, 30003].includes(errorCode(error) ?? 0)) {
      // 已失去权限的元信息与原文一并清除，恢复后需重新读取来源列表。
      const redacted: ChatKnowledgeSourceVO = {
        sourceId: row.sourceId,
        number: row.number,
        status: errorCode(error) === 20001 ? 'FORBIDDEN' : 'UNAVAILABLE',
        knowledgeBaseName: null,
        documentId: null,
        chunkId: null,
        score: null,
        document: null,
      }
      selected.value = redacted
      result.value?.retrievals.forEach((retrieval) => {
        retrieval.sources = retrieval.sources.map((source) =>
          source.sourceId === row.sourceId ? redacted : source,
        )
      })
    }
  } finally {
    if (isCurrent(key) && sequence === previewSequence) previewLoading.value = false
  }
}

async function backToSources() {
  previewSequence += 1
  selected.value = null
  preview.value = null
  previewError.value = null
  previewLoading.value = false
  await nextTick()
  if (sourceButton?.isConnected && !sourceButton.disabled) sourceButton.focus()
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(1040px, 100vw)"
    append-to-body
    destroy-on-close
    class="workspace-knowledge-sources"
    :show-close="false"
    @close="emit('update:modelValue', false)"
    @closed="emit('closed')"
  >
    <template #header="{ titleId, titleClass }">
      <div class="sources-heading">
        <h2 :id="titleId" :class="titleClass">本轮检索参考</h2>
        <p>本轮自动检索提供的参考资料，打开原文时重新核验访问权限。</p>
      </div>
      <el-button
        class="sources-close"
        text
        aria-label="关闭本轮检索参考"
        @click="emit('update:modelValue', false)"
      >
        <el-icon><Close /></el-icon>
      </el-button>
    </template>
    <div v-if="!canRead" class="source-feedback" role="status">当前账号没有工作区访问权限。</div>
    <div v-else class="sources-reader" :class="{ 'sources-reader--reading': selected }">
      <section class="sources-list" aria-label="本轮来源列表" :aria-busy="listLoading">
        <header class="sources-list__heading">
          <h3>检索记录</h3>
          <el-button text :loading="listLoading" @click="loadSources">刷新来源</el-button>
        </header>
        <div v-if="listError" class="source-feedback" role="alert">
          <strong>检索参考加载失败</strong>
          <p>{{ failure(listError) }}</p>
          <el-button type="primary" plain @click="loadSources">重新加载</el-button>
        </div>
        <el-skeleton v-else-if="listLoading" :rows="5" animated />
        <div v-else-if="result?.status === 'NOT_RECORDED'" class="source-feedback" role="status">
          <strong>本轮未留存检索参考</strong>
          <p>尚未查询到来源保存记录，不能据此判断是否检索命中。可稍后刷新核对。</p>
        </div>
        <div v-else-if="result && !result.retrievals.length" class="source-feedback" role="status">
          <strong>本轮未留存自动检索记录</strong>
          <p>本条消息没有自动检索参考，工具返回的内容可在执行记录中核对。</p>
        </div>
        <div v-else class="sources-list__records">
          <section
            v-for="(retrieval, index) in result?.retrievals ?? []"
            :key="index"
            class="retrieval-record"
          >
            <div
              class="retrieval-status"
              :class="{ 'retrieval-status--partial': retrieval.status === 'DEGRADED' }"
            >
              <strong>{{ retrievalLabel(retrieval) }}</strong
              ><span>{{ retrieval.agentCode }}</span>
              <p v-if="retrieval.status === 'DEGRADED'">
                检索结果不完整，已返回的资料仍可逐条核对。
              </p>
            </div>
            <button
              v-for="row in retrieval.sources"
              :key="row.sourceId"
              type="button"
              class="knowledge-source"
              :disabled="row.status !== 'AVAILABLE' || !row.document"
              :aria-pressed="selected?.sourceId === row.sourceId"
              @click="readSource(row, $event)"
            >
              <strong
                ><span class="source-number">[{{ row.number }}]</span>
                {{ row.document?.title || row.knowledgeBaseName || '来源不可预览' }}</strong
              >
              <span>{{ sourceLabel(row) }}</span>
              <span v-if="row.status === 'AVAILABLE' && row.knowledgeBaseName">{{
                row.knowledgeBaseName
              }}</span>
            </button>
          </section>
        </div>
      </section>
      <section class="source-reader" aria-label="检索来源原文预览" :aria-busy="previewLoading">
        <template v-if="selected">
          <header class="source-reader__heading">
            <el-button plain class="source-back" @click="backToSources">返回来源列表</el-button>
            <h3 ref="previewHeading" tabindex="-1">{{ selected.document?.title || '文档原文' }}</h3>
            <el-button
              v-if="selected.status === 'AVAILABLE'"
              :loading="previewLoading"
              @click="readSource(selected)"
              >重新核验</el-button
            >
          </header>
          <el-skeleton v-if="previewLoading" :rows="8" animated />
          <div v-else-if="previewError" class="source-feedback" role="alert">
            <strong>原文暂不可读</strong>
            <p>{{ failure(previewError) }}</p>
            <el-button v-if="retryPreview" type="primary" plain @click="readSource(selected)"
              >重试读取</el-button
            >
            <el-button v-else plain @click="loadSources">刷新来源</el-button>
          </div>
          <template v-else-if="preview">
            <div class="source-version">
              <el-tag size="small">v{{ preview.document.versionNo }}</el-tag>
              <span>{{ preview.document.currentRevision ? '当前文档修订' : '历史文档修订' }}</span>
              <span>修订 #{{ preview.document.revisionId }}</span>
            </div>
            <el-collapse>
              <el-collapse-item title="查看来源详情" name="details">
                <dl class="source-details">
                  <dt>知识库</dt>
                  <dd>{{ selected.knowledgeBaseName || '未记录' }}</dd>
                  <dt>文档源</dt>
                  <dd>{{ preview.document.sourceName || '未记录' }}</dd>
                  <dt>来源版本</dt>
                  <dd>{{ preview.document.sourceVersion || '未记录' }}</dd>
                  <dt>原文位置</dt>
                  <dd>{{ preview.document.sourceUri || '未记录' }}</dd>
                  <dt>来源更新时间</dt>
                  <dd>{{ preview.document.sourceUpdatedAt || '未记录' }}</dd>
                  <dt>内容指纹</dt>
                  <dd>{{ preview.document.contentHash || '未记录' }}</dd>
                </dl>
              </el-collapse-item>
            </el-collapse>
            <pre
              v-if="preview.content"
              class="source-paper"
              tabindex="0"
              aria-label="已授权的检索来源原文"
              >{{ preview.content }}</pre
            >
            <p v-else class="source-feedback">此文档修订未包含正文。</p>
          </template>
        </template>
        <div v-else class="source-feedback source-feedback--empty">
          <strong>选择一条参考查看原文</strong>
          <p>按本轮保存的文档版本核对资料和来源。</p>
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.sources-heading {
  min-width: 0;
}
.sources-heading h2 {
  margin: 0;
  color: var(--cw-text);
  font-size: 19px;
}
.sources-heading p {
  margin: 7px 0 0;
  color: var(--cw-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.sources-reader {
  display: grid;
  grid-template-columns: minmax(230px, 32%) minmax(0, 1fr);
  gap: 24px;
  height: 100%;
  min-height: 0;
}
.sources-list,
.source-reader {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}
.sources-list {
  border-right: 1px solid var(--cw-line);
  padding-right: 20px;
}
.sources-list__heading,
.source-reader__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
}
.sources-list h3,
.source-reader h3 {
  margin: 0;
  font-size: 15px;
  color: var(--cw-text);
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.sources-list__records {
  overflow-y: auto;
  min-height: 0;
  padding: 3px;
}
.retrieval-record + .retrieval-record {
  margin-top: 24px;
}
.retrieval-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px 10px;
  margin-bottom: 10px;
  font-size: 12px;
}
.retrieval-status strong {
  color: var(--cw-text);
}
.retrieval-status span,
.retrieval-status p {
  color: var(--cw-text-muted);
  overflow-wrap: anywhere;
}
.retrieval-status p {
  margin: 0;
  line-height: 1.6;
}
.retrieval-status--partial {
  padding: 10px;
  border-left: 2px solid var(--el-color-warning);
  background: var(--cw-canvas);
}
.knowledge-source {
  display: flex;
  flex-direction: column;
  gap: 7px;
  width: 100%;
  padding: 14px;
  margin-bottom: 8px;
  border: 1px solid var(--cw-line);
  border-radius: 9px;
  background: var(--cw-paper);
  color: var(--cw-text);
  text-align: left;
  font: inherit;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.knowledge-source strong {
  font-size: 14px;
  line-height: 1.5;
}
.knowledge-source > span {
  color: var(--cw-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.source-number {
  color: var(--theme-primary-solid);
  font-family: ui-monospace, monospace;
  margin-right: 4px;
}
.knowledge-source:hover:not(:disabled),
.knowledge-source[aria-pressed='true'] {
  border-color: var(--theme-primary-solid);
  background: color-mix(in srgb, var(--theme-primary-solid) 7%, var(--cw-paper));
}
.knowledge-source:focus-visible {
  outline: 2px solid var(--theme-primary-solid);
  outline-offset: 2px;
}
.knowledge-source:disabled {
  background: var(--cw-canvas);
  cursor: default;
}
.source-reader {
  overflow-y: auto;
}
.source-reader__heading {
  position: sticky;
  top: 0;
  z-index: 1;
  padding-bottom: 8px;
  background: var(--cw-paper);
}
.source-back {
  display: none;
}
.source-feedback {
  padding: 18px 4px;
  color: var(--cw-text);
  line-height: 1.7;
}
.source-feedback p {
  color: var(--cw-text-muted);
  font-size: 13px;
}
.source-feedback--empty {
  margin: auto;
  text-align: center;
  max-width: 300px;
}
.source-version {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
  font-size: 12px;
  color: var(--cw-text-muted);
}
.source-details {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 12px;
}
.source-details dt {
  color: var(--cw-text-muted);
}
.source-details dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.source-paper {
  margin: 0;
  padding: 24px;
  border: 1px solid var(--cw-line);
  border-radius: 10px;
  background: var(--cw-paper);
  color: var(--cw-text);
  font: 15px/1.9 var(--cw-font-family, system-ui, sans-serif);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
@media (max-width: 700px) {
  .sources-reader :deep(.el-button),
  .sources-close {
    min-width: 44px;
    min-height: 44px;
  }
  .sources-reader {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
  .sources-list {
    border-right: 0;
    padding-right: 0;
  }
  .source-reader {
    display: none;
  }
  .sources-reader--reading .sources-list {
    display: none;
  }
  .sources-reader--reading .source-reader {
    display: flex;
  }
  .source-reader__heading {
    flex-wrap: wrap;
  }
  .source-reader__heading h3 {
    order: 3;
    flex-basis: 100%;
  }
  .source-back {
    display: inline-flex;
  }
  .source-paper {
    padding: 16px;
    font-size: 14px;
  }
  .source-details {
    grid-template-columns: 76px minmax(0, 1fr);
  }
}
</style>
