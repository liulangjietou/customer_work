<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePersistedRef } from './composables/useToolStorage'
import { useDebouncedEffect } from './composables/useDebouncedEffect'
import { buildHighlightHtml, findAllMatches, toMatchRows } from './composables/regexUtils'
import type { MatchRow } from './composables/regexUtils'
import CopyButton from './CopyButton.vue'

const FLAG_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'g', label: 'g（全局）' },
  { value: 'i', label: 'i（忽略大小写）' },
  { value: 'm', label: 'm（多行）' },
  { value: 's', label: 's（. 匹配换行）' },
]

const pattern = usePersistedRef('regex:pattern', '')
const flagList = usePersistedRef<string[]>('regex:flags', ['g'])
const testText = usePersistedRef('regex:testText', '')
const replacement = usePersistedRef('regex:replacement', '')

const patternError = ref('')
const highlightHtml = ref('')
const matchRows = ref<MatchRow[]>([])
const replaceResult = ref('')
const replaceError = ref('')

const flags = computed(() => [...flagList.value].sort().join(''))

function compute() {
  patternError.value = ''
  replaceError.value = ''
  highlightHtml.value = ''
  matchRows.value = []
  replaceResult.value = ''

  if (!pattern.value) {
    highlightHtml.value = escapeForEmpty(testText.value)
    return
  }

  let re: RegExp
  try {
    re = new RegExp(pattern.value, flags.value)
  } catch (e) {
    patternError.value = `正则表达式非法：${e instanceof Error ? e.message : String(e)}`
    highlightHtml.value = escapeForEmpty(testText.value)
    return
  }

  const matches = findAllMatches(re, testText.value)
  highlightHtml.value = buildHighlightHtml(testText.value, matches)
  matchRows.value = toMatchRows(matches)

  if (replacement.value !== '') {
    try {
      const reForReplace = new RegExp(pattern.value, flags.value)
      replaceResult.value = testText.value.replace(reForReplace, replacement.value)
    } catch (e) {
      replaceError.value = e instanceof Error ? e.message : String(e)
    }
  }
}

// 简单转义，无匹配/无 pattern 时仍要把原文安全地渲染进高亮预览区
function escapeForEmpty(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

useDebouncedEffect([pattern, flagList, testText, replacement], compute)
</script>

<template>
  <div class="regex-tool">
    <el-form label-width="90px">
      <el-form-item label="正则表达式">
        <el-input v-model="pattern" placeholder="不含分隔符，如 \\d+" clearable>
          <template #prepend>/</template>
          <template #append>/{{ flags }}</template>
        </el-input>
        <div v-if="patternError" class="field-error">{{ patternError }}</div>
      </el-form-item>

      <el-form-item label="标志位">
        <el-checkbox-group v-model="flagList">
          <el-checkbox v-for="opt in FLAG_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>

      <el-form-item label="测试文本">
        <textarea v-model="testText" class="code-textarea" spellcheck="false" placeholder="输入待测试文本…" />
      </el-form-item>
    </el-form>

    <div class="result-section">
      <div class="result-block">
        <div class="pane-header"><span>高亮预览</span></div>
        <div class="highlight-preview" v-html="highlightHtml || '&nbsp;'" />
      </div>

      <div class="result-block">
        <div class="pane-header"><span>匹配列表（{{ matchRows.length }} 项）</span></div>
        <el-table :data="matchRows" size="small" border max-height="260">
          <el-table-column prop="index" label="序号" width="60" />
          <el-table-column prop="position" label="位置" width="100" />
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column prop="groups" label="捕获组" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="matchRows.length === 0 && !patternError" description="暂无匹配" :image-size="50" />
      </div>
    </div>

    <el-form label-width="90px" class="replace-form">
      <el-form-item label="替换表达式">
        <el-input v-model="replacement" placeholder="支持 $1 $2 等分组引用，$&amp; 表示整体匹配" clearable />
      </el-form-item>
    </el-form>

    <div class="result-block">
      <div class="pane-header">
        <span>替换预览</span>
        <CopyButton :text="replaceResult" label="替换结果" />
      </div>
      <div v-if="replaceError" class="field-error">{{ replaceError }}</div>
      <pre class="code-block">{{ replaceResult || '（替换表达式为空时不展示结果）' }}</pre>
    </div>
  </div>
</template>

<style scoped>
.regex-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.code-textarea {
  min-height: 160px;
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

.result-section {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.result-block {
  flex: 1;
  min-width: 280px;
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

.highlight-preview {
  flex: 1;
  min-height: 160px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  overflow: auto;
}

.highlight-preview :deep(mark) {
  background: var(--el-color-warning-light-5, #f3d19e);
  color: inherit;
  border-radius: 2px;
  padding: 0 1px;
}

.replace-form {
  max-width: 640px;
}

.code-block {
  margin: 0;
  padding: 10px 12px;
  min-height: 60px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
