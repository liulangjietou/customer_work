<script setup lang="ts">
// Base64/URL/Hex 三个"编码↔解码"面板共用的展示壳：模式切换 + 输入 + 实时输出，逻辑全在
// useEncodeDecodeTool 里，本组件只负责渲染，保持"组件薄"。
import { useEncodeDecodeTool } from './composables/useEncodeDecodeTool'
import CopyButton from './CopyButton.vue'

const props = defineProps<{
  toolKey: string
  encodeFn: (input: string) => string
  decodeFn: (input: string) => string
  placeholder?: string
}>()

const { mode, input, output, error } = useEncodeDecodeTool(props.toolKey, props.encodeFn, props.decodeFn)
</script>

<template>
  <div class="codec-panel">
    <el-radio-group v-model="mode" size="default" class="mode-group">
      <el-radio-button value="encode">编码</el-radio-button>
      <el-radio-button value="decode">解码</el-radio-button>
    </el-radio-group>

    <div class="panes">
      <div class="pane">
        <div class="pane-header"><span>输入</span></div>
        <textarea
          v-model="input"
          class="code-textarea"
          spellcheck="false"
          :placeholder="placeholder || (mode === 'encode' ? '输入原文…' : '输入待解码内容…')"
        />
        <div v-if="error" class="field-error">{{ error }}</div>
      </div>
      <div class="pane">
        <div class="pane-header">
          <span>输出</span>
          <CopyButton :text="output" label="结果" />
        </div>
        <textarea class="code-textarea" readonly spellcheck="false" :value="output" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.codec-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mode-group {
  align-self: flex-start;
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
  min-height: 220px;
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

.code-textarea[readonly] {
  background: var(--el-fill-color-light);
}

.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
