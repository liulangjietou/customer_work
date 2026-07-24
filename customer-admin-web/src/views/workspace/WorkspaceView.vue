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
      <h2 class="agent-title">{{ agentNode?.name ?? agentCode }}</h2>
      <div class="header-actions">
        <!-- 与 ThemeToolbar 里两个按钮保持同一胶囊规格（34px 高/17px 圆角/13px 字号），蓝底白字 -->
        <button type="button" class="knowledge-btn" @click="knowledgeVisible = true">代码知识库</button>
        <ThemeToolbar :on-new-session="newSession" />
      </div>
    </div>
    <KnowledgeDrawer v-model="knowledgeVisible" />
    <!-- 站内信跳转承接：AI 代码审查任务完成后的完整报告，只读展示（不出"打开文件"/"一键生成修复"，
         弹窗打开时不一定处于该会话的 VibeCoding 面板上下文，见 ReviewReport 组件注释） -->
    <el-dialog v-model="reviewDialogVisible" title="AI 代码审查报告" width="640px" destroy-on-close>
      <ReviewReport v-if="reviewDialogResult" :result="reviewDialogResult" :interactive="false" />
    </el-dialog>
    <el-tabs v-model="activeTab" type="border-card" class="workspace-tabs">
      <el-tab-pane label="对话" name="chat">
        <!-- workspace/:agentCode 是同一条路由，只换参数，Vue Router 默认复用组件实例——
             不加 :key 的话切换智能体时 ChatPanel 内部的 messages/sessionId 状态会跟着串到下一个
             智能体身上。加 :key 强制按 agentCode 重建实例，天然顺带把上一个智能体未结束的 SSE
             流也一起 abort 掉（复用 ChatPanel 自己 onUnmounted 里已有的 abortStream 逻辑）。 -->
        <ChatPanel ref="chatPanelRef" :key="agentCode" :agent-code="agentCode" :initial-session-id="initialSessionId" />
      </el-tab-pane>
      <el-tab-pane v-if="supportsVibeCoding" label="VibeCoding" name="vibecoding">
        <VibeCodingPanel ref="vibeCodingPanelRef" :key="agentCode" :agent-code="agentCode" :initial-session-id="initialSessionId" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.workspace-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.agent-title {
  margin: 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 代码知识库：蓝底白字胶囊，规格与 ThemeToolbar 的主题色/新建会话按钮一致（34px 高/17px 圆角） */
.knowledge-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 18px;
  border: none;
  border-radius: 17px;
  background: #409eff;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
  transition: box-shadow 0.2s, transform 0.2s, filter 0.2s;
}

.knowledge-btn:hover {
  filter: brightness(1.08);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18);
  transform: translateY(-1px);
}

.knowledge-btn:active {
  transform: translateY(0);
  filter: brightness(0.96);
}

.workspace-tabs {
  flex: 1;
  min-height: 0;
}
</style>
