<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import type { MenuNode } from '@/types/api'
import ChatPanel from './ChatPanel.vue'
import VibeCodingPanel from './VibeCodingPanel.vue'
import ThemeToolbar from '@/components/ThemeToolbar.vue'

const props = defineProps<{ agentCode: string }>()
const menuStore = useMenuStore()
const route = useRoute()
// 从 Project 详情页"点会话跳回工作区"带过来的目标会话 id（?sessionId=xxx），只在首次挂载时读一次，
// ChatPanel 内部 openSession 打开后由它自己的会话状态接管，不需要响应式跟随路由变化。
const initialSessionId = computed(() => (route.query.sessionId as string) || undefined)

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
const chatPanelRef = ref<InstanceType<typeof ChatPanel>>()
const vibeCodingPanelRef = ref<InstanceType<typeof VibeCodingPanel>>()

function newSession() {
  if (activeTab.value === 'vibecoding') {
    vibeCodingPanelRef.value?.newSession()
  } else {
    chatPanelRef.value?.newSession()
  }
}
</script>

<template>
  <div class="workspace-view">
    <div class="workspace-header">
      <h2 class="agent-title">{{ agentNode?.name ?? agentCode }}</h2>
      <ThemeToolbar :on-new-session="newSession" />
    </div>
    <el-tabs v-model="activeTab" type="border-card" class="workspace-tabs">
      <el-tab-pane label="对话" name="chat">
        <!-- workspace/:agentCode 是同一条路由，只换参数，Vue Router 默认复用组件实例——
             不加 :key 的话切换智能体时 ChatPanel 内部的 messages/sessionId 状态会跟着串到下一个
             智能体身上。加 :key 强制按 agentCode 重建实例，天然顺带把上一个智能体未结束的 SSE
             流也一起 abort 掉（复用 ChatPanel 自己 onUnmounted 里已有的 abortStream 逻辑）。 -->
        <ChatPanel ref="chatPanelRef" :key="agentCode" :agent-code="agentCode" :initial-session-id="initialSessionId" />
      </el-tab-pane>
      <el-tab-pane v-if="supportsVibeCoding" label="VibeCoding" name="vibecoding">
        <VibeCodingPanel ref="vibeCodingPanelRef" :key="agentCode" :agent-code="agentCode" />
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

.workspace-tabs {
  flex: 1;
  min-height: 0;
}
</style>
