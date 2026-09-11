<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { pageKnowledgeVersionDocuments, previewKnowledgeVersionDocument } from '@/api/knowledgeBase'
import type {
  KnowledgeBaseVersionVO,
  KnowledgeDocumentPreviewVO,
  KnowledgeVersionDocumentVO,
} from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  knowledgeBaseId: number | null
  knowledgeBaseName: string
  version: KnowledgeBaseVersionVO | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const auth = useAuthStore()
const canRead = computed(
  () =>
    !!auth.token &&
    auth.hasPermission('knowledge-base:view') &&
    auth.hasPermission('knowledge-base:source-preview'),
)
const contextKey = computed(() =>
  [props.modelValue, props.knowledgeBaseId, props.version?.id, auth.token, canRead.value].join('|'),
)
const documents = ref<KnowledgeVersionDocumentVO[]>([])
const page = ref(1)
const total = ref(0)
const listLoading = ref(false)
const listError = ref<unknown>(null)
const selected = ref<KnowledgeVersionDocumentVO | null>(null)
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
  if (errorCode(error) === 30003) return '此版本来源已不可用，文档可能已撤回或清理。'
  return '暂时无法读取来源，请检查连接后重试。'
}
function isCurrent(key: number) {
  return key === generation && props.modelValue && canRead.value
}

/** 切换版本、权限或登录主体时立即移除原文，等待新一代请求完成。 */
watch(
  contextKey,
  () => {
    generation += 1
    listSequence += 1
    previewSequence += 1
    documents.value = []
    selected.value = null
    preview.value = null
    listLoading.value = false
    previewLoading.value = false
    listError.value = null
    previewError.value = null
    page.value = 1
    total.value = 0
    sourceButton = null
  },
  { flush: 'sync' },
)
watch(
  contextKey,
  () => {
    if (props.modelValue && canRead.value) void loadDocuments(1)
  },
  { immediate: true, flush: 'post' },
)
onBeforeUnmount(() => {
  generation += 1
})

async function loadDocuments(pageNum: number) {
  if (!canRead.value || !props.knowledgeBaseId || !props.version) return
  const key = generation
  const sequence = ++listSequence
  const knowledgeBaseId = props.knowledgeBaseId
  const versionId = props.version.id
  previewSequence += 1
  selected.value = null
  preview.value = null
  previewLoading.value = false
  previewError.value = null
  listLoading.value = true
  listError.value = null
  documents.value = []
  page.value = pageNum
  try {
    const result = await pageKnowledgeVersionDocuments(knowledgeBaseId, versionId, pageNum)
    if (!isCurrent(key) || sequence !== listSequence) return
    if (
      !Array.isArray(result.list) ||
      result.list.some(
        (row) => row.knowledgeBaseId !== knowledgeBaseId || row.versionId !== versionId,
      )
    ) {
      throw new Error('来源与所选知识版本不一致')
    }
    documents.value = result.list
    total.value = result.total
  } catch (error) {
    if (isCurrent(key) && sequence === listSequence) listError.value = error
  } finally {
    if (isCurrent(key) && sequence === listSequence) listLoading.value = false
  }
}

async function readDocument(row: KnowledgeVersionDocumentVO, event?: Event) {
  if (!canRead.value || row.status !== 'AVAILABLE' || !props.knowledgeBaseId || !props.version)
    return
  const key = generation
  const sequence = ++previewSequence
  const knowledgeBaseId = props.knowledgeBaseId
  const versionId = props.version.id
  selected.value = row
  preview.value = null
  previewError.value = null
  previewLoading.value = true
  if (event?.currentTarget instanceof HTMLButtonElement) sourceButton = event.currentTarget
  await nextTick()
  if (isCurrent(key) && sequence === previewSequence) previewHeading.value?.focus()
  try {
    const result = await previewKnowledgeVersionDocument(knowledgeBaseId, versionId, row.revisionId)
    if (!isCurrent(key) || sequence !== previewSequence) return
    if (
      result.document?.knowledgeBaseId !== knowledgeBaseId ||
      result.document.versionId !== versionId ||
      result.document.revisionId !== row.revisionId ||
      result.document.status !== 'AVAILABLE' ||
      typeof result.content !== 'string'
    )
      throw new Error('原文与所选文档修订不一致')
    preview.value = result
  } catch (error) {
    if (isCurrent(key) && sequence === previewSequence) previewError.value = error
  } finally {
    if (isCurrent(key) && sequence === previewSequence) previewLoading.value = false
  }
}

async function backToDocuments() {
  previewSequence += 1
  selected.value = null
  preview.value = null
  previewError.value = null
  previewLoading.value = false
  await nextTick()
  if (sourceButton?.isConnected) sourceButton.focus()
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(1120px, 100%)"
    append-to-body
    destroy-on-close
    class="knowledge-version-preview"
    @close="emit('update:modelValue', false)"
  >
    <template #header>
      <div class="version-heading">
        <strong>版本原文 · {{ knowledgeBaseName }}</strong>
        <span>v{{ version?.versionNo }} · {{ version?.documentCount ?? 0 }} 篇文档</span>
      </div>
    </template>
    <el-alert
      v-if="!canRead"
      title="当前账号没有原文预览权限"
      description="请联系管理员授予“预览知识原文”权限。"
      type="info"
      :closable="false"
      show-icon
      role="status"
    />
    <div v-else class="version-reader" :class="{ 'version-reader--reading': selected }">
      <section class="version-documents" aria-label="版本文档列表" :aria-busy="listLoading">
        <div class="version-documents__heading">
          <h3>文档目录</h3>
          <el-button text :loading="listLoading" @click="loadDocuments(page)">刷新目录</el-button>
        </div>
        <div v-if="listError" class="source-feedback" role="alert">
          <strong>文档目录加载失败</strong>
          <p>{{ failure(listError) }}</p>
          <el-button type="primary" plain @click="loadDocuments(page)">重新加载</el-button>
        </div>
        <el-skeleton v-else-if="listLoading" :rows="5" animated />
        <div v-else-if="documents.length === 0" class="source-feedback" role="status">
          <strong>此版本没有可预览的文档</strong>
          <p>外部检索连接的正文由对应知识服务管理。</p>
        </div>
        <div v-else class="version-documents__list">
          <button
            v-for="row in documents"
            :key="row.revisionId"
            type="button"
            class="version-document"
            :aria-pressed="selected?.revisionId === row.revisionId"
            :disabled="row.status !== 'AVAILABLE'"
            @click="readDocument(row, $event)"
          >
            <strong>{{ row.title || row.externalId || '来源不可预览' }}</strong>
            <span v-if="row.status === 'AVAILABLE'"
              >{{ row.sourceName || '文档源' }} · {{ row.sourceVersion || '未记录来源版本' }}</span
            >
            <span v-else>{{ row.status === 'FORBIDDEN' ? '当前账号无权预览' : '来源已失效' }}</span>
          </button>
        </div>
        <el-pagination
          v-if="!listLoading && !listError && total > 20"
          :current-page="page"
          :page-size="20"
          :total="total"
          layout="prev, pager, next"
          :pager-count="5"
          @current-change="loadDocuments"
        />
      </section>

      <section class="version-source" aria-label="文档原文预览" :aria-busy="previewLoading">
        <template v-if="selected">
          <div class="version-source__heading">
            <el-button class="version-source__back" plain @click="backToDocuments"
              >返回文档列表</el-button
            >
            <h3 ref="previewHeading" tabindex="-1">
              {{ preview?.document.title || selected.title || '文档原文' }}
            </h3>
            <el-button :loading="previewLoading" @click="readDocument(selected)"
              >重新核验</el-button
            >
          </div>
          <el-skeleton v-if="previewLoading" :rows="8" animated />
          <div v-else-if="previewError" class="source-feedback" role="alert">
            <strong>原文暂不可读</strong>
            <p>{{ failure(previewError) }}</p>
            <el-button type="primary" plain @click="readDocument(selected)">重试读取</el-button>
          </div>
          <template v-else-if="preview">
            <div class="source-version-note">
              <el-tag size="small">v{{ preview.document.versionNo }}</el-tag>
              <span>{{ preview.document.currentRevision ? '当前文档修订' : '历史文档修订' }}</span>
              <span>修订 #{{ preview.document.revisionId }}</span>
            </div>
            <el-collapse class="source-details">
              <el-collapse-item title="查看来源详情" name="details">
                <dl>
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
              aria-label="已授权的版本原文"
              >{{ preview.content }}</pre
            >
            <p v-else class="source-feedback">此文档修订未包含正文。</p>
          </template>
        </template>
        <div v-else class="source-feedback source-feedback--empty">
          <strong>选择一篇文档查看原文</strong>
          <p>按所选版本核对来源、修订与正文，打开时会重新检查访问权限。</p>
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.version-heading {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
}
.version-heading strong {
  color: var(--cw-text);
  font-size: 19px;
  overflow-wrap: anywhere;
}
.version-heading span,
.version-document span,
.source-feedback p {
  color: var(--cw-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.version-reader {
  display: grid;
  grid-template-columns: minmax(230px, 29%) minmax(0, 1fr);
  gap: 24px;
  height: 100%;
  min-height: 0;
}
.version-documents,
.version-source {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.version-documents {
  border-right: 1px solid var(--cw-line);
  padding-right: 20px;
}
.version-documents__heading,
.version-source__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
}
.version-documents h3,
.version-source h3 {
  margin: 0;
  font-size: 16px;
  line-height: 1.5;
  color: var(--cw-text);
  overflow-wrap: anywhere;
}
.version-documents__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  min-height: 0;
}
.version-document {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex-shrink: 0;
  width: 100%;
  min-height: 72px;
  padding: 14px;
  border: 1px solid var(--cw-line);
  border-radius: 9px;
  background: var(--cw-paper);
  color: var(--cw-text);
  text-align: left;
  cursor: pointer;
  font: inherit;
  overflow-wrap: anywhere;
}
.version-document strong {
  font-size: 14px;
  line-height: 1.5;
}
.version-document:hover:not(:disabled),
.version-document[aria-pressed='true'] {
  border-color: var(--theme-primary-solid);
  background: color-mix(in srgb, var(--theme-primary-solid) 7%, var(--cw-paper));
}
.version-document:focus-visible {
  outline: 2px solid var(--theme-primary-solid);
  outline-offset: 2px;
}
.version-document:disabled {
  cursor: default;
  background: var(--cw-canvas);
}
.version-source__heading {
  position: sticky;
  top: 0;
  z-index: 1;
  padding-bottom: 8px;
  background: var(--cw-paper);
}
.version-source__back {
  display: none;
}
.version-source {
  overflow-y: auto;
}
.source-version-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 9px;
  color: var(--cw-text-muted);
  font-size: 13px;
}
.source-feedback {
  padding: 18px 4px;
  color: var(--cw-text);
  line-height: 1.7;
}
.source-feedback--empty {
  margin: auto;
  text-align: center;
  max-width: 360px;
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
.source-details {
  flex-shrink: 0;
}
.source-details dl {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 9px 14px;
  margin: 0;
}
.source-details dt {
  color: var(--cw-text-muted);
}
.source-details dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 720px) {
  .version-reader {
    grid-template-columns: minmax(0, 1fr);
  }
  .version-documents {
    border: 0;
    padding: 0;
  }
  .version-source {
    display: none;
  }
  .version-reader--reading .version-documents {
    display: none;
  }
  .version-reader--reading .version-source {
    display: flex;
  }
  .version-source__heading {
    flex-wrap: wrap;
  }
  .version-source__heading h3 {
    order: 3;
    width: 100%;
  }
  .version-source__back {
    display: inline-flex;
  }
  .version-reader :deep(.el-button),
  .version-reader :deep(.el-pager li),
  .version-reader :deep(.btn-prev),
  .version-reader :deep(.btn-next) {
    min-height: 44px;
  }
  .version-reader :deep(.el-pager li) {
    min-width: 36px;
  }
  .source-paper {
    padding: 18px;
  }
}
</style>
