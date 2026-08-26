<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = withDefaults(defineProps<{
  text: string
  variant?: 'default' | 'answer'
}>(), { variant: 'default' })

const html = computed(() => renderMarkdown(props.text))
</script>

<template>
  <div class="markdown-body" :class="`markdown-body--${variant}`" v-html="html" />
</template>

<style scoped>
.markdown-body {
  word-break: break-word;
}

.markdown-body--answer {
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.75;
}

.markdown-body :deep(p) {
  margin: 0 0 8px;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(pre) {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
  overflow-x: auto;
  margin: 8px 0;
}

.markdown-body :deep(code) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
}

.markdown-body :deep(p code) {
  color: var(--theme-primary, var(--el-color-primary));
  background: color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 9%, transparent);
  padding: 2px 4px;
  border-radius: 4px;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 6px 10px;
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--el-fill-color-light);
  font-weight: 600;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 0 12px;
  border-left: 3px solid var(--theme-primary, var(--el-color-primary));
  color: var(--el-text-color-secondary);
}

.markdown-body--answer :deep(h1),
.markdown-body--answer :deep(h2),
.markdown-body--answer :deep(h3),
.markdown-body--answer :deep(h4) {
  margin: 18px 0 8px;
  color: var(--el-text-color-primary);
  font-weight: 650;
  line-height: 1.35;
}

.markdown-body--answer :deep(h1:first-child),
.markdown-body--answer :deep(h2:first-child),
.markdown-body--answer :deep(h3:first-child) {
  margin-top: 2px;
}

.markdown-body--answer :deep(h1) { font-size: 20px; }
.markdown-body--answer :deep(h2) { font-size: 17px; }
.markdown-body--answer :deep(h3) { font-size: 15px; }

.markdown-body--answer :deep(a) {
  color: var(--theme-primary, var(--el-color-primary));
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.markdown-body--answer :deep(li + li) {
  margin-top: 3px;
}

.markdown-body--answer :deep(hr) {
  margin: 18px 0;
  border: 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.markdown-body--answer :deep(pre) {
  margin: 12px 0;
  padding: 13px 14px;
  border-radius: 9px;
  line-height: 1.6;
}

.markdown-body--answer :deep(table) {
  display: block;
  overflow-x: auto;
  border-radius: 8px;
}
</style>
