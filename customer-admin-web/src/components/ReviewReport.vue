<script setup lang="ts">
import type { ReviewIssue, ReviewResult } from '@/types/api'

/**
 * AI 代码审查报告渲染（从 VibeCodingPanel 的 Git 助手抽屉抽出，供该处与 WorkspaceView
 * 站内信跳转弹窗共用，保证两处渲染一致）。
 *
 * interactive=false（如站内信跳转弹窗）：只读展示，文件名不可点、不出"一键生成修复"按钮——
 * 那两个交互都要落到具体会话（打开文件预览/把修复意见发回对话），弹窗打开时不一定处于
 * 该会话的 VibeCoding 面板上下文里，强行接线路径过长，只读展示反而是更贴合场景的最小实现。
 */
const props = withDefaults(
  defineProps<{
    result: ReviewResult
    interactive?: boolean
    /** "一键生成修复"按钮禁用态（如会话正在流式对话中），仅 interactive=true 时有意义。 */
    fixDisabled?: boolean
  }>(),
  { interactive: true, fixDisabled: false },
)

const emit = defineEmits<{
  (e: 'open-file', issue: ReviewIssue): void
  (e: 'generate-fix'): void
}>()

/** 严重级别 → Element Plus tag 类型（着色）。 */
function severityTagType(severity: ReviewIssue['severity']): 'danger' | 'warning' | 'info' {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'info'
}

/** 按严重级别分组审查意见（CRITICAL → WARNING → SUGGESTION 顺序）。 */
function groupedIssues(issues: ReviewIssue[]): Array<{ severity: ReviewIssue['severity']; items: ReviewIssue[] }> {
  const order: ReviewIssue['severity'][] = ['CRITICAL', 'WARNING', 'SUGGESTION']
  return order
    .map((severity) => ({ severity, items: issues.filter((i) => i.severity === severity) }))
    .filter((g) => g.items.length > 0)
}

function handleOpenFile(issue: ReviewIssue) {
  if (!props.interactive || !issue.file) return
  emit('open-file', issue)
}
</script>

<template>
  <div class="review-report">
    <p v-if="result.summary" class="review-summary">{{ result.summary }}</p>
    <el-empty
      v-if="result.issues.length === 0"
      description="未发现结构化问题"
      :image-size="40"
    />
    <div v-else class="review-issues">
      <div v-for="group in groupedIssues(result.issues)" :key="group.severity" class="review-group">
        <div class="review-group-header">
          <el-tag :type="severityTagType(group.severity)" size="small" effect="dark">
            {{ group.severity }}
          </el-tag>
          <span class="review-group-count">{{ group.items.length }} 项</span>
        </div>
        <div
          v-for="(issue, ii) in group.items"
          :key="ii"
          class="review-issue"
          :class="`review-issue--${group.severity.toLowerCase()}`"
        >
          <div class="review-issue-loc">
            <el-tag size="small" class="review-issue-category">{{ issue.category }}</el-tag>
            <el-link
              v-if="interactive"
              type="primary"
              :underline="false"
              @click="handleOpenFile(issue)"
            >
              {{ issue.file }}<template v-if="issue.line">:{{ issue.line }}</template>
            </el-link>
            <span v-else class="review-issue-file-text">
              {{ issue.file }}<template v-if="issue.line">:{{ issue.line }}</template>
            </span>
          </div>
          <div class="review-issue-message">{{ issue.message }}</div>
          <div v-if="issue.suggestion" class="review-issue-suggestion">建议：{{ issue.suggestion }}</div>
        </div>
      </div>
    </div>
    <el-button
      v-if="interactive && result.issues.some((i) => i.severity === 'CRITICAL' || i.severity === 'WARNING')"
      type="warning"
      size="small"
      class="review-fix-btn"
      :disabled="fixDisabled"
      @click="emit('generate-fix')"
    >
      一键生成修复
    </el-button>
  </div>
</template>

<style scoped>
.review-summary {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
  margin: 0 0 8px;
}

.review-group {
  margin-bottom: 10px;
}

.review-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.review-group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.review-issue {
  margin-bottom: 6px;
  padding: 6px 8px;
  border-left: 3px solid var(--el-border-color);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.review-issue--critical {
  border-left-color: var(--el-color-danger);
}

.review-issue--warning {
  border-left-color: var(--el-color-warning);
}

.review-issue--suggestion {
  border-left-color: var(--el-color-info);
}

.review-issue-loc {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}

.review-issue-category {
  flex-shrink: 0;
}

.review-issue-file-text {
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.review-issue-message {
  font-size: 13px;
  color: var(--el-text-color-primary);
  line-height: 1.5;
}

.review-issue-suggestion {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.review-fix-btn {
  margin-top: 8px;
}
</style>
