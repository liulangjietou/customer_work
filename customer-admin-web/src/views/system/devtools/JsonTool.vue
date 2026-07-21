<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import hljs from 'highlight.js/lib/core'
import json from 'highlight.js/lib/languages/json'
import { usePersistedRef } from './composables/useToolStorage'
import { useDebouncedEffect } from './composables/useDebouncedEffect'
import { getLineContent, lineColToIndex, locateJsonError } from './composables/jsonLint'
import { decodeUnicodeEscapes, escapeJsonText, unescapeJsonText } from './composables/jsonTransform'
import CopyButton from './CopyButton.vue'

hljs.registerLanguage('json', json)

// 超过 1MB 只给性能提示，不阻断计算（验收要求：大文本不阻断）
const LARGE_TEXT_THRESHOLD_BYTES = 1024 * 1024

type OperationKey = 'format' | 'compress' | 'escape' | 'unescape' | 'unicode'

const operations: Array<{ key: OperationKey; label: string }> = [
  { key: 'format', label: '格式化' },
  { key: 'compress', label: '压缩' },
  { key: 'escape', label: '转义' },
  { key: 'unescape', label: '去转义' },
  { key: 'unicode', label: 'Unicode→中文' },
]

const input = usePersistedRef('json:input', '')
const operation = usePersistedRef<OperationKey>('json:operation', 'format')
const indentSize = usePersistedRef<2 | 4>('json:indent', 2)

const output = ref('')
const highlightedOutput = ref('')
const errorInfo = ref<{ line: number; col: number; message: string } | null>(null)
const inputRef = ref<HTMLTextAreaElement>()

const inputSizeBytes = computed(() => new Blob([input.value]).size)
const isLargeText = computed(() => inputSizeBytes.value > LARGE_TEXT_THRESHOLD_BYTES)

/** 需要先验证是合法 JSON 才能继续的操作（格式化/压缩/去转义复用同一套语法定位）。 */
function needsValidJson(op: OperationKey): boolean {
  return op === 'format' || op === 'compress'
}

function compute() {
  errorInfo.value = null
  if (!input.value) {
    output.value = ''
    highlightedOutput.value = ''
    return
  }

  try {
    if (operation.value === 'escape') {
      output.value = escapeJsonText(input.value)
    } else if (operation.value === 'unescape') {
      // 去转义复用 JSON 语法扫描器统一定位错误：先按"应为合法转义串"扫一遍，拿不到明确行列时退化为纯文案提示
      output.value = unescapeJsonText(input.value)
    } else if (operation.value === 'unicode') {
      output.value = decodeUnicodeEscapes(input.value)
    } else if (needsValidJson(operation.value)) {
      const failure = locateJsonError(input.value)
      if (failure) {
        errorInfo.value = failure
        output.value = ''
        highlightedOutput.value = ''
        return
      }
      const parsed = JSON.parse(input.value)
      output.value = operation.value === 'format' ? JSON.stringify(parsed, null, indentSize.value) : JSON.stringify(parsed)
    }
  } catch (e) {
    errorInfo.value = { line: 1, col: 1, message: e instanceof Error ? e.message : String(e) }
    output.value = ''
    highlightedOutput.value = ''
    return
  }

  try {
    highlightedOutput.value = hljs.highlight(output.value, { language: 'json' }).value
  } catch {
    highlightedOutput.value = output.value
  }
}

useDebouncedEffect([input, operation, indentSize], compute)

const errorLinePreview = computed(() => {
  if (!errorInfo.value) return ''
  return getLineContent(input.value, errorInfo.value.line)
})

/** 错误行预览下方的插入符号（^），指向具体列，帮助在长行里快速定位。 */
const errorCaretPadding = computed(() => {
  if (!errorInfo.value) return ''
  return ' '.repeat(Math.max(0, errorInfo.value.col - 1))
})

/** "定位到输入框"：把错误位置对应的字符选中并滚动到可视区域，不在计算过程中抢焦点，只在用户主动点击时触发。 */
async function locateInInput() {
  if (!errorInfo.value || !inputRef.value) return
  const index = lineColToIndex(input.value, errorInfo.value.line, errorInfo.value.col)
  await nextTick()
  inputRef.value.focus()
  inputRef.value.setSelectionRange(index, index + 1)
}
</script>

<template>
  <div class="json-tool">
    <div class="toolbar">
      <el-radio-group v-model="operation" size="default">
        <el-radio-button v-for="op in operations" :key="op.key" :value="op.key">{{ op.label }}</el-radio-button>
      </el-radio-group>
      <el-radio-group v-if="operation === 'format'" v-model="indentSize" size="default" class="indent-group">
        <el-radio-button :value="2">缩进 2</el-radio-button>
        <el-radio-button :value="4">缩进 4</el-radio-button>
      </el-radio-group>
    </div>

    <el-alert
      v-if="isLargeText"
      type="info"
      :closable="false"
      show-icon
      class="perf-hint"
      :title="`文本较大（约 ${(inputSizeBytes / 1024 / 1024).toFixed(1)} MB），实时计算可能有明显延迟，但不会阻断`"
    />

    <div class="panes">
      <div class="pane">
        <div class="pane-header">
          <span>输入</span>
        </div>
        <textarea
          ref="inputRef"
          v-model="input"
          class="code-textarea"
          spellcheck="false"
          placeholder="粘贴 JSON 或任意文本…"
        />
        <div v-if="errorInfo" class="error-hint">
          <div class="error-message">
            第 {{ errorInfo.line }} 行第 {{ errorInfo.col }} 列：{{ errorInfo.message }}
            <el-button link type="primary" size="small" @click="locateInInput">定位到输入框</el-button>
          </div>
          <pre class="error-line-preview">{{ errorLinePreview }}</pre>
          <pre class="error-caret">{{ errorCaretPadding }}^</pre>
        </div>
      </div>

      <div class="pane">
        <div class="pane-header">
          <span>输出</span>
          <CopyButton :text="output" label="结果" />
        </div>
        <el-scrollbar class="output-scroll">
          <pre class="code-block"><code v-html="highlightedOutput || '&nbsp;'" /></pre>
        </el-scrollbar>
      </div>
    </div>
  </div>
</template>

<style scoped>
.json-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.perf-hint {
  margin: 0;
}

.panes {
  display: flex;
  gap: 16px;
  min-height: 480px;
}

.pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
  min-height: 24px;
}

.code-textarea {
  flex: 1;
  min-height: 440px;
  width: 100%;
  box-sizing: border-box;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  resize: vertical;
  outline: none;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
}

.code-textarea:focus {
  border-color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-bg-color);
}

.output-scroll {
  flex: 1;
  min-height: 440px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.code-block {
  margin: 0;
  padding: 10px 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.error-hint {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--el-color-danger-light-9, rgba(245, 108, 108, 0.08));
  border: 1px solid var(--el-color-danger-light-5, rgba(245, 108, 108, 0.3));
}

.error-message {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-color-danger);
}

.error-line-preview,
.error-caret {
  margin: 4px 0 0;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
  white-space: pre;
  overflow-x: auto;
}

.error-caret {
  color: var(--el-color-danger);
  font-weight: 700;
}

.indent-group {
  margin-left: 4px;
}
</style>
