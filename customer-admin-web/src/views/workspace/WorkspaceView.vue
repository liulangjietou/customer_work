<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import { getReviewTask } from '@/api/vibecoding'
import type { MenuNode, ReviewResult } from '@/types/api'
import ChatPanel from './ChatPanel.vue'
import VibeCodingPanel from './VibeCodingPanel.vue'
import KnowledgeDrawer from './KnowledgeDrawer.vue'
import ThemeToolbar from '@/components/ThemeToolbar.vue'
import ReviewReport from '@/components/ReviewReport.vue'

// 供 MainLayout 的 <keep-alive :include> 按组件名精确命中——离开本页（切到其它菜单）时不销毁，
// 只是 deactivated；SSE 流不会被 onUnmounted 打断，切回来消息能接着看，见 ChatPanel/VibeCodingPanel
// 的 onUnmounted 注释。
defineOptions({ name: 'WorkspaceView' })

const props = defineProps<{ agentCode: string }>()
const menuStore = useMenuStore()
const route = useRoute()
const router = useRouter()
// 从 Project 详情页"点会话跳回工作区"、或「智能体耗时统计」页"打开会话"带过来的目标会话 id
// （?sessionId=xxx），ChatPanel/VibeCodingPanel 各自用 watch(immediate) 响应式跟随，
// 支持同一实例二次带新 sessionId 跳入（keep-alive 下不会重新 mount，见两个面板内部注释）。
const initialSessionId = computed(() => (route.query.sessionId as string) || undefined)
// 与 sessionId 配套的目标 Tab：VibeCoding 会话跳 VibeCoding 面板，其余（含缺省）落对话面板。
const initialMode = computed(() => (route.query.mode as string) || undefined)

function findNode(nodes: MenuNode[], agentCode: string): MenuNode | null {
  for (const node of nodes) {
    if (node.dynamic && node.agentCode === agentCode) {
      return node
    }
    const found = findNode(node.children ?? [], agentCode)
    if (found) {
      return found
    }
  }
  return null
}

const agentNode = computed(() => findNode(menuStore.tree, props.agentCode))
const agentDisplayName = computed(() => agentNode.value?.name ?? props.agentCode)
const supportsVibeCoding = computed(() => agentNode.value?.capabilities?.includes('vibecoding') ?? false)

// 主题色/新建会话工具栏上提到 Tab 上方后，"新建会话"要按当前激活的 Tab 分发到对应面板
const activeTab = ref<'chat' | 'vibecoding'>('chat')

// keep-alive 复用同一实例后，切智能体不再重建本组件，activeTab 会带着上一个智能体的值——
// 从支持 vibecoding 的智能体的 VibeCoding tab 切到不支持的智能体时，'vibecoding' 这个 tab-pane
// 不渲染，el-tabs 的 v-model 指向不存在的 pane，整个 tab 内容区直接空白（实测踩过）。
// 旧代码每次切换都销毁重建、activeTab 隐式重置，keep-alive 把这个隐式重置保没了，这里补成显式的。
watch(supportsVibeCoding, (supported) => {
  if (!supported && activeTab.value === 'vibecoding') {
    activeTab.value = 'chat'
  }
})

// 带 ?mode= 跳入时按目标 Tab 切换（「智能体耗时统计」页"打开会话"的入口）。supportsVibeCoding
// 依赖 menuStore 异步拉到的智能体能力树，跟 initialMode 一起 watch，防止菜单树晚到位时错过一次切换。
watch(
  [initialMode, supportsVibeCoding],
  ([mode, supported]) => {
    if (mode === 'vibecoding' && supported) {
      activeTab.value = 'vibecoding'
    } else if (mode === 'chat') {
      activeTab.value = 'chat'
    }
  },
  { immediate: true },
)

const chatPanelRef = ref<InstanceType<typeof ChatPanel>>()
const vibeCodingPanelRef = ref<InstanceType<typeof VibeCodingPanel>>()

function newSession() {
  if (activeTab.value === 'vibecoding') {
    vibeCodingPanelRef.value?.newSession()
  } else {
    chatPanelRef.value?.newSession()
  }
}

// 代码知识库抽屉（P3-2）
const knowledgeVisible = ref(false)

// 站内信跳转承接（AI 代码审查异步化）：link 形如 /workspace/{agentCode}?reviewTask={taskId}，
// 挂载时读一次并回查任务终态，用完整报告弹窗展示，与 VibeCodingPanel 内的 Git 助手抽屉共用
// ReviewReport 渲染，保证两处样式一致。
const reviewDialogVisible = ref(false)
const reviewDialogResult = ref<ReviewResult | null>(null)

/**
 * 处理 ?reviewTask= 查询参数：无论查询成功与否都立即从 query 里摘掉，避免用户刷新页面/
 * 前进后退时重复弹出同一份报告。
 *
 * 用 watch(immediate) 而不是 onMounted：WorkspaceView 是 keep-alive 精确缓存的路由组件
 * （见 MainLayout 里的 include: ['WorkspaceView'] 注释），/workspace/:agentCode 换参数时
 * Vue Router 默认复用同一实例、不重新 mount——站内信链接从当前已打开的工作区页跳到
 * 另一个 reviewTask 查询参数时，onMounted 不会再触发，只有响应式的 route.query 变化能感知到。
 */
async function handleReviewTaskQuery(taskIdRaw: string | null) {
  if (!taskIdRaw) return
  const taskId = Number(taskIdRaw)
  const restQuery = { ...route.query }
  delete restQuery.reviewTask
  await router.replace({ query: restQuery })
  if (!Number.isFinite(taskId)) return
  try {
    const task = await getReviewTask(props.agentCode, taskId)
    if (task.status === 'SUCCESS' && task.result) {
      reviewDialogResult.value = task.result
      reviewDialogVisible.value = true
    } else if (task.status === 'RUNNING') {
      ElMessage.info('AI 代码审查仍在进行中，请稍后再查看')
    } else if (task.status === 'FAILED') {
      ElMessage.error(task.errorMsg || 'AI 代码审查失败')
    }
  } catch (error) {
    ElMessage.error('审查任务查询失败：' + (error instanceof Error ? error.message : String(error)))
  }
}

watch(
  () => route.query.reviewTask,
  (taskIdRaw) => {
    if (typeof taskIdRaw === 'string') {
      handleReviewTaskQuery(taskIdRaw)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="workspace-view">
    <div class="workspace-header">
      <div class="agent-identity">
        <span class="agent-mark" aria-hidden="true">&lt;/&gt;</span>
        <div class="agent-copy">
          <h1 class="agent-title">{{ agentDisplayName }}</h1>
          <div class="agent-meta">
            <span>智能体工作台</span>
            <span class="meta-separator" aria-hidden="true">/</span>
            <code class="agent-code">{{ agentCode }}</code>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <button
          type="button"
          class="knowledge-btn"
          aria-label="打开代码知识库"
          @click="knowledgeVisible = true"
        >
          <el-icon aria-hidden="true"><Collection /></el-icon>
          <span>代码知识库</span>
        </button>
        <ThemeToolbar :on-new-session="newSession" />
      </div>
    </div>
    <KnowledgeDrawer v-model="knowledgeVisible" />
    <!-- 站内信跳转承接：AI 代码审查任务完成后的完整报告，只读展示（不出"打开文件"/"一键生成修复"，
         弹窗打开时不一定处于该会话的 VibeCoding 面板上下文，见 ReviewReport 组件注释） -->
    <el-dialog v-model="reviewDialogVisible" title="AI 代码审查报告" width="640px" destroy-on-close>
      <ReviewReport v-if="reviewDialogResult" :result="reviewDialogResult" :interactive="false" />
    </el-dialog>
    <el-tabs v-model="activeTab" class="workspace-tabs">
      <el-tab-pane label="对话" name="chat">
        <!-- workspace/:agentCode 是同一条路由，只换参数，Vue Router 默认复用组件实例——
             不加 :key 的话切换智能体时 ChatPanel 内部的 messages/sessionId 状态会跟着串到下一个
             智能体身上。加 :key 强制按 agentCode 重建实例，天然顺带把上一个智能体未结束的 SSE
             流也一起 abort 掉（复用 ChatPanel 自己 onUnmounted 里已有的 abortStream 逻辑）。 -->
        <ChatPanel
          ref="chatPanelRef"
          :key="agentCode"
          :agent-code="agentCode"
          :assistant-name="agentDisplayName"
          :initial-session-id="initialSessionId"
        />
      </el-tab-pane>
      <el-tab-pane v-if="supportsVibeCoding" label="VibeCoding" name="vibecoding">
        <VibeCodingPanel
          ref="vibeCodingPanelRef"
          :key="agentCode"
          :agent-code="agentCode"
          :assistant-name="agentDisplayName"
          :initial-session-id="initialSessionId"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.workspace-view {
  height: 100%;
  box-sizing: border-box;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 18px;
  box-shadow: var(--cw-card-shadow, 0 1px 3px rgb(16 24 40 / 6%));
}

.workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  min-height: 64px;
  gap: 24px;
  padding: 10px 18px 10px 20px;
  background: color-mix(in srgb, var(--el-bg-color) 96%, var(--theme-primary, var(--el-color-primary)) 4%);
}

.agent-identity {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.agent-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  color: #fff;
  background: linear-gradient(145deg, #2d374b, #151c2a);
  border-radius: 11px;
  box-shadow: 0 5px 14px rgb(18 25 39 / 18%);
  font-family: "SFMono-Regular", "JetBrains Mono", Consolas, monospace;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.08em;
}

.agent-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.agent-title {
  margin: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-family: "SF Pro Display", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 20px;
  font-weight: 680;
  letter-spacing: -0.02em;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-meta {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 7px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.2;
}

.meta-separator {
  color: var(--el-text-color-placeholder);
}

.agent-code {
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-family: "SFMono-Regular", "JetBrains Mono", Consolas, monospace;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 8px;
}

.knowledge-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  padding: 0 12px;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 560;
  box-shadow: 0 1px 2px rgb(16 24 40 / 3%);
  transition: border-color 160ms ease, color 160ms ease, transform 160ms ease, box-shadow 160ms ease;
}

.knowledge-btn .el-icon {
  color: #2563eb;
  font-size: 15px;
}

.knowledge-btn:hover {
  color: var(--el-text-color-primary);
  border-color: var(--theme-primary, var(--el-color-primary));
  box-shadow: 0 4px 10px rgb(16 24 40 / 8%);
  transform: translateY(-1px);
}

.knowledge-btn:active {
  transform: translateY(0);
}

.knowledge-btn:focus-visible {
  outline: 0;
  box-shadow:
    0 0 0 2px var(--el-bg-color),
    0 0 0 4px var(--el-text-color-primary);
}

.workspace-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.workspace-tabs :deep(.el-tabs__header) {
  flex: 0 0 auto;
  margin: 0;
  padding: 0 20px;
  background: color-mix(in srgb, var(--el-bg-color) 97%, var(--theme-primary, var(--el-color-primary)) 3%);
}

.workspace-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--el-border-color-lighter);
}

.workspace-tabs :deep(.el-tabs__item) {
  height: 42px;
  padding: 0 2px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 560;
}

.workspace-tabs :deep(.el-tabs__item + .el-tabs__item) {
  margin-left: 22px;
}

.workspace-tabs :deep(.el-tabs__item:hover),
.workspace-tabs :deep(.el-tabs__item.is-active) {
  color: var(--el-text-color-primary);
}

.workspace-tabs :deep(.el-tabs__active-bar) {
  height: 2px;
  background: var(--theme-primary, var(--el-color-primary));
  border-radius: 2px 2px 0 0;
}

.workspace-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  container-name: workspace-panel;
  container-type: inline-size;
}

.workspace-tabs :deep(.el-tab-pane) {
  height: 100%;
  min-height: 0;
}

@media (max-width: 760px) {
  .workspace-view {
    border-radius: 12px;
  }

  .workspace-header {
    align-items: flex-start;
    flex-direction: column;
    min-height: auto;
    gap: 10px;
    padding: 12px;
  }

  .agent-mark {
    flex-basis: 36px;
    width: 36px;
    height: 36px;
  }

  .agent-title {
    font-size: 18px;
  }

  .header-actions {
    justify-content: flex-end;
    width: 100%;
  }

  .workspace-tabs :deep(.el-tabs__header) {
    padding: 0 12px;
  }
}

@media (max-width: 480px) {
  .knowledge-btn {
    width: 36px;
    padding: 0;
  }

  .knowledge-btn span {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .knowledge-btn {
    transition: none;
  }

  .knowledge-btn:hover {
    transform: none;
  }
}
</style>
