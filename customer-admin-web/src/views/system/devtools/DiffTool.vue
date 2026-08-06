<script setup lang="ts">
// 文本比对：LCS 行级 diff 走后端（starter 的 DiffDevToolOps），与智能体侧 text_diff 同一实现。
import { computed, ref } from 'vue'
import { diffText, type TextDiffResponse } from '@/api/devtools'
import { usePersistedRef } from './composables/useToolStorage'

const oldText = usePersistedRef('diff:oldText', '')
const newText = usePersistedRef('diff:newText', '')
const ignoreWhitespace = usePersistedRef('diff:ignoreWhitespace', false)
const ignoreCase = usePersistedRef('diff:ignoreCase', false)

const loading = ref(false)
const result = ref<TextDiffResponse | null>(null)

/** 只看差异时过滤掉相同行——配置文件比对里相同行往往占绝大多数。 */
const onlyDifferences = usePersistedRef('diff:onlyDifferences', false)

const displayLines = computed(() => {
  if (!result.value) return []
  return onlyDifferences.value
    ? result.value.lines.filter((line) => line.type !== 'EQUAL')
    : result.value.lines
})

const summary = computed(() => {
  const data = result.value
  if (!data) return null
  if (data.identical) {
    return { type: 'success' as const, text: '两段文本完全一致' }
  }
  return { type: 'warning' as const, text: `新增 ${data.addedLines} 行，删除 ${data.deletedLines} 行` }
})

async function handleDiff() {
  loading.value = true
  try {
    result.value = await diffText({
      oldText: oldText.value,
      newText: newText.value,
      ignoreWhitespace: ignoreWhitespace.value,
      ignoreCase: ignoreCase.value,
    })
  } finally {
    loading.value = false
  }
}

function handleSwap() {
  const temp = oldText.value
  oldText.value = newText.value
  newText.value = temp
  result.value = null
}

function handleClear() {
  oldText.value = ''
  newText.value = ''
  result.value = null
}

function lineMarker(type: string): string {
  if (type === 'INSERT') return '+'
  if (type === 'DELETE') return '-'
  return ' '
}
</script>

<template>
  <div class="diff-tool">
    <div class="panes">
      <div class="pane">
        <div class="pane-header"><span>原文本</span></div>
        <textarea v-model="oldText" class="code-textarea" spellcheck="false" placeholder="粘贴改动前的内容…" />
      </div>
      <div class="pane">
        <div class="pane-header"><span>新文本</span></div>
        <textarea v-model="newText" class="code-textarea" spellcheck="false" placeholder="粘贴改动后的内容…" />
      </div>
    </div>

    <div class="actions">
      <el-button type="primary" :loading="loading" @click="handleDiff">比对</el-button>
      <el-button @click="handleSwap">交换两侧</el-button>
      <el-button @click="handleClear">清空</el-button>
      <el-checkbox v-model="ignoreWhitespace">忽略行首尾空白</el-checkbox>
      <el-checkbox v-model="ignoreCase">忽略大小写</el-checkbox>
      <el-checkbox v-model="onlyDifferences">只看差异行</el-checkbox>
    </div>

    <template v-if="result">
      <el-alert v-if="summary" :type="summary.type" :closable="false" show-icon :title="summary.text" />
      <el-alert
        v-if="result.truncated"
        type="warning"
        :closable="false"
        show-icon
        :title="`差异过多，只展示前 ${result.lines.length} 行（共 ${result.totalLines} 行）`"
      />

      <div v-if="displayLines.length" class="diff-result">
        <div
          v-for="(line, index) in displayLines"
          :key="index"
          class="diff-line"
          :class="`diff-${line.type.toLowerCase()}`"
        >
          <span class="line-no">{{ line.oldLineNo > 0 ? line.oldLineNo : '' }}</span>
          <span class="line-no">{{ line.newLineNo > 0 ? line.newLineNo : '' }}</span>
          <span class="marker">{{ lineMarker(line.type) }}</span>
          <span class="content">{{ line.content }}</span>
        </div>
      </div>
      <el-empty v-else-if="onlyDifferences" description="没有差异行" :image-size="80" />
    </template>
  </div>
</template>

<style scoped>
.diff-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panes {
  display: flex;
  gap: 16px;
}

.pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.pane-header {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
  min-height: 24px;
}

.code-textarea {
  min-height: 200px;
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

.actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.diff-result {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  overflow: auto;
  max-height: 520px;
  background: var(--el-fill-color-lighter);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
}

.diff-line {
  display: flex;
  align-items: baseline;
  padding: 0 8px;
  white-space: pre-wrap;
  word-break: break-all;
}

.line-no {
  flex: none;
  width: 44px;
  text-align: right;
  padding-right: 8px;
  color: var(--el-text-color-placeholder);
  user-select: none;
}

.marker {
  flex: none;
  width: 16px;
  user-select: none;
}

.content {
  flex: 1;
  min-width: 0;
}

.diff-insert {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
}

.diff-delete {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger-dark-2);
}
</style>
