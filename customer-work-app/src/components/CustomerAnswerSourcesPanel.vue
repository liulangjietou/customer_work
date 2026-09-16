<script setup lang="ts">
import type { AxiosError } from 'axios'
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Popup } from 'vant'
import {
  fetchCustomerAnswerSources,
  fetchCustomerAnswerSourcePreview,
} from '@/api/customerAnswerSources'
import type {
  CustomerAnswerSource,
  CustomerAnswerSourcePreview,
} from '@/types/customerAnswerSources'

const props = defineProps<{
  open: boolean
  sessionId: string
  messageId: string
  identityKey: string
  trigger?: HTMLElement | null
}>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const headingId = useId()
const visible = ref(false)
const panel = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const sources = ref<CustomerAnswerSource[]>([])
const preview = ref<CustomerAnswerSourcePreview | null>(null)
const sourceIndex = ref<number | null>(null)
const loading = ref(false)
const errorMessage = ref('')
let requestVersion = 0
let disposed = false
let returnFocus: HTMLElement | null = null

/** 在请求开始、目标改变及关闭时同步清除旧内容；拒绝让已撤权正文留在面板中。 */
function clearContent() {
  requestVersion++
  sources.value = []
  preview.value = null
  errorMessage.value = ''
  loading.value = false
}

function isCurrent(version: number) {
  return !disposed && visible.value && props.open && version === requestVersion
}

function failureMessage(error: unknown) {
  const status = (error as AxiosError | undefined)?.response?.status
  return status === 404
    ? '参考资料当前不可访问，可能已移除或访问权限已变化。'
    : '参考资料暂时无法加载，请稍后重试。'
}

async function loadList(focusIndex?: number) {
  clearContent()
  sourceIndex.value = null
  loading.value = true
  const version = requestVersion
  try {
    const result = await fetchCustomerAnswerSources(props.sessionId, props.messageId)
    if (!isCurrent(version)) return
    sources.value = result
  } catch (error) {
    if (isCurrent(version)) errorMessage.value = failureMessage(error)
  } finally {
    if (isCurrent(version)) {
      loading.value = false
      if (focusIndex !== undefined) {
        await nextTick()
        if (isCurrent(version)) {
          const sourceButton = panel.value?.querySelector<HTMLButtonElement>(
            `[data-source-index="${focusIndex}"]`,
          )
          const focusTarget = sourceButton ?? closeButton.value
          focusTarget?.focus()
        }
      }
    }
  }
}

async function loadPreview(index: number) {
  clearContent()
  sourceIndex.value = index
  loading.value = true
  const version = requestVersion
  try {
    const result = await fetchCustomerAnswerSourcePreview(props.sessionId, props.messageId, index)
    if (!isCurrent(version)) return
    if (result.status === 'UNAVAILABLE') {
      errorMessage.value = '资料已不可用，可能已移除或访问权限已变化。'
    } else {
      preview.value = result
    }
  } catch (error) {
    if (isCurrent(version)) errorMessage.value = failureMessage(error)
  } finally {
    if (isCurrent(version)) {
      loading.value = false
      await nextTick()
      if (isCurrent(version))
        panel.value?.querySelector<HTMLElement>('.source-preview-heading, [role="alert"]')?.focus()
    }
  }
}

function retry() {
  if (sourceIndex.value === null) void loadList()
  else void loadPreview(sourceIndex.value)
}

function restoreFocus() {
  void nextTick(() => {
    if (!visible.value && returnFocus?.isConnected) returnFocus.focus()
  })
}

function dismiss(restore = true) {
  clearContent()
  visible.value = false
  emit('update:open', false)
  if (restore) restoreFocus()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    dismiss()
    return
  }
  if (event.key !== 'Tab') return
  const buttons = panel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
  if (!buttons?.length) return
  const first = buttons[0]!
  const last = buttons[buttons.length - 1]!
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (
    !event.shiftKey &&
    (document.activeElement === last || !panel.value?.contains(document.activeElement))
  ) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  () => [props.open, props.sessionId, props.messageId, props.identityKey] as const,
  ([open, sessionId, messageId, identity], previous) => {
    if (previous && identity !== previous[3]) {
      dismiss(false)
      return
    }
    visible.value = open && Boolean(sessionId && messageId)
    if (!visible.value) {
      clearContent()
      if (previous?.[0]) restoreFocus()
      return
    }
    if (!previous?.[0])
      returnFocus = props.trigger ?? (document.activeElement as HTMLElement | null)
    // 同一轮更新会分别设置会话和消息；先同步失效，等属性更新完成后只请求最终目标。
    clearContent()
    const version = requestVersion
    void nextTick(() => {
      if (!isCurrent(version)) return
      void loadList()
      closeButton.value?.focus()
    })
  },
  { immediate: true, flush: 'sync' },
)

onBeforeUnmount(() => {
  disposed = true
  clearContent()
})
</script>

<template>
  <Popup
    :show="visible"
    position="bottom"
    round
    teleport="body"
    destroy-on-close
    :duration="0.16"
    class="answer-sources-popup"
    aria-modal="true"
    :aria-labelledby="headingId"
    @update:show="
      (value) => {
        if (!value) dismiss()
      }
    "
    @keydown="onKeydown"
  >
    <section v-if="visible" ref="panel" class="source-panel">
      <header class="source-header">
        <button
          v-if="sourceIndex !== null"
          type="button"
          data-action="back"
          @click="loadList(sourceIndex)"
        >
          返回列表
        </button>
        <span v-else aria-hidden="true"></span>
        <h2 :id="headingId">参考资料</h2>
        <button ref="closeButton" type="button" data-action="close" @click="dismiss()">关闭</button>
      </header>

      <div class="source-body" :aria-busy="loading">
        <div v-if="loading" class="source-state" role="status">正在核对参考资料…</div>
        <div v-else-if="errorMessage" class="source-state source-error" role="alert" tabindex="-1">
          <p>{{ errorMessage }}</p>
          <button type="button" class="source-button" data-action="retry" @click="retry">
            重新核对
          </button>
        </div>

        <template v-else-if="sourceIndex === null">
          <p class="source-intro">查看本轮检索时保存的资料段落。</p>
          <div v-if="sources.length === 0" class="source-state">
            <strong>这条答复没有可打开的参考资料</strong>
            <p>旧消息或文字线索可能没有保存可回看的原文。</p>
          </div>
          <ul v-else class="source-list">
            <li v-for="source in sources" :key="source.sourceIndex">
              <button
                v-if="source.status === 'AVAILABLE'"
                type="button"
                class="source-option"
                :data-source-index="source.sourceIndex"
                @click="loadPreview(source.sourceIndex)"
              >
                <strong>{{ source.title || '未命名文档' }}</strong>
                <span v-if="source.knowledgeBase" class="source-knowledge-base">{{
                  source.knowledgeBase
                }}</span>
                <span v-if="source.versionNo !== null" class="source-version"
                  >知识库版本 {{ source.versionNo }}</span
                >
                <span v-if="source.sourceVersion" class="source-version"
                  >来源版本 {{ source.sourceVersion }}</span
                >
                <span class="source-open-label">查看历史段落 ›</span>
              </button>
              <div v-else class="source-unavailable">资料已不可用</div>
            </li>
          </ul>
        </template>

        <article v-else-if="preview" class="source-preview">
          <h3 class="source-preview-heading" tabindex="-1">{{ preview.title || '未命名文档' }}</h3>
          <p v-if="preview.knowledgeBase" class="source-knowledge-base">
            {{ preview.knowledgeBase }}
          </p>
          <div class="source-versions">
            <span v-if="preview.versionNo !== null" class="source-version"
              >知识库版本 {{ preview.versionNo }}</span
            >
            <span v-if="preview.sourceVersion" class="source-version"
              >来源版本 {{ preview.sourceVersion }}</span
            >
          </div>
          <p class="source-intro">本轮检索时保存的历史段落</p>
          <pre v-if="preview.content !== null" class="source-content">{{ preview.content }}</pre>
          <p v-else class="source-state">资料未提供可展示的段落</p>
        </article>
      </div>

      <footer v-if="!errorMessage" class="source-footer">
        <button
          v-if="sourceIndex === null"
          type="button"
          class="source-button"
          data-action="refresh-sources"
          :disabled="loading"
          @click="loadList()"
        >
          刷新参考资料
        </button>
        <button
          v-else
          type="button"
          class="source-button"
          data-action="refresh-preview"
          :disabled="loading"
          @click="retry"
        >
          重新核验原文
        </button>
      </footer>
    </section>
  </Popup>
</template>

<style scoped>
.answer-sources-popup {
  left: max(0px, calc((100vw - var(--cw-shell-width, 480px)) / 2));
  width: min(100%, var(--cw-shell-width, 480px));
  height: min(86dvh, 780px);
  color: var(--cw-text-primary, #1d2637);
  background: var(--cw-card-bg, #fff);
}

.source-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.source-header {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 76px;
  align-items: center;
  gap: 4px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--cw-line, #e3e7ef);
}

.source-header h2 {
  margin: 0;
  text-align: center;
  font-size: 17px;
  font-weight: 700;
}

.source-panel button {
  min-width: 44px;
  min-height: 44px;
  border: 0;
  border-radius: 10px;
  font: inherit;
  cursor: pointer;
}

.source-panel button:focus-visible,
.source-panel [tabindex]:focus-visible {
  outline: 3px solid var(--cw-focus-ring, rgba(49, 108, 255, 0.32));
  outline-offset: 2px;
}

.source-header button {
  padding: 8px;
  background: transparent;
  color: var(--cw-primary, #315bde);
  font-size: 14px;
}

.source-body {
  flex: 1;
  min-height: 0;
  padding: 16px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.source-intro,
.source-knowledge-base,
.source-version {
  color: var(--cw-text-secondary, #626d80);
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.source-intro {
  margin: 0 0 14px;
}
.source-knowledge-base {
  display: block;
  margin: 5px 0;
}
.source-version {
  display: block;
}

.source-list {
  display: grid;
  gap: 12px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.source-panel .source-option {
  display: block;
  width: 100%;
  padding: 14px;
  border: 1px solid var(--cw-line, #e3e7ef);
  background: var(--cw-page-bg, #f6f8fb);
  text-align: left;
}

.source-option strong,
.source-preview-heading {
  display: block;
  font-size: 16px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.source-open-label {
  display: block;
  margin-top: 10px;
  color: var(--cw-primary, #315bde);
  font-size: 14px;
}

.source-state {
  padding: 28px 4px;
  color: var(--cw-text-secondary, #626d80);
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.source-state p {
  margin: 0 0 14px;
}
.source-state strong {
  display: block;
  margin-bottom: 8px;
  color: var(--cw-text-primary, #1d2637);
}
.source-unavailable {
  padding: 18px 14px;
  border-radius: 10px;
  background: var(--cw-page-bg, #f6f8fb);
  color: var(--cw-text-secondary, #626d80);
}
.source-preview-heading {
  margin: 0;
}
.source-versions {
  margin: 8px 0 18px;
}

.source-content {
  margin: 0;
  padding: 14px;
  border: 1px solid var(--cw-line, #e3e7ef);
  border-radius: 12px;
  background: var(--cw-page-bg, #f6f8fb);
  font: inherit;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.source-footer {
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--cw-line, #e3e7ef);
}

.source-panel .source-button {
  padding: 10px 16px;
  background: var(--cw-primary-soft, #eaf1ff);
  color: var(--cw-primary, #315bde);
  font-size: 14px;
}

.source-footer .source-button {
  width: 100%;
}
.source-panel button:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
