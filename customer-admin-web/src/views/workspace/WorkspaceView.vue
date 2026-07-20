<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import type { MenuNode } from '@/types/api'
import ChatPanel from './ChatPanel.vue'
import VibeCodingPanel from './VibeCodingPanel.vue'
import KnowledgeDrawer from './KnowledgeDrawer.vue'
import ThemeToolbar from '@/components/ThemeToolbar.vue'

// 供 MainLayout 的 <keep-alive :include> 按组件名精确命中——离开本页（切到其它菜单）时不销毁，
// 只是 deactivated；SSE 流不会被 onUnmounted 打断，切回来消息能接着看，见 ChatPanel/VibeCodingPanel
// 的 onUnmounted 注释。
defineOptions({ name: 'WorkspaceView' })

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

// keep-alive 复用同一实例后，切智能体不再重建本组件，activeTab 会带着上一个智能体的值——
// 从支持 vibecoding 的智能体的 VibeCoding tab 切到不支持的智能体时，'vibecoding' 这个 tab-pane
// 不渲染，el-tabs 的 v-model 指向不存在的 pane，整个 tab 内容区直接空白（实测踩过）。
// 旧代码每次切换都销毁重建、activeTab 隐式重置，keep-alive 把这个隐式重置保没了，这里补成显式的。
watch(supportsVibeCoding, (supported) => {
  if (!supported && activeTab.value === 'vibecoding') {
    activeTab.value = 'chat'
  }
})
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
