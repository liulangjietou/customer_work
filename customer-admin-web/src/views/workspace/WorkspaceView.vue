<script setup lang="ts">
import { computed } from 'vue'
import { useMenuStore } from '@/store/menu'
import type { MenuNode } from '@/types/api'
import ChatPanel from './ChatPanel.vue'
import VibeCodingPanel from './VibeCodingPanel.vue'

const props = defineProps<{ agentCode: string }>()
const menuStore = useMenuStore()

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
</script>

<template>
  <div class="workspace-view">
    <h2 class="agent-title">{{ agentNode?.name ?? agentCode }}</h2>
    <el-tabs type="border-card" class="workspace-tabs">
      <el-tab-pane label="对话">
        <!-- workspace/:agentCode 是同一条路由，只换参数，Vue Router 默认复用组件实例——
             不加 :key 的话切换智能体时 ChatPanel 内部的 messages/sessionId 状态会跟着串到下一个
             智能体身上。加 :key 强制按 agentCode 重建实例，天然顺带把上一个智能体未结束的 SSE
             流也一起 abort 掉（复用 ChatPanel 自己 onUnmounted 里已有的 abortStream 逻辑）。 -->
        <ChatPanel :key="agentCode" :agent-code="agentCode" />
      </el-tab-pane>
      <el-tab-pane v-if="supportsVibeCoding" label="VibeCoding">
        <VibeCodingPanel :key="agentCode" :agent-code="agentCode" />
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

.agent-title {
  margin: 0 0 12px;
}

.workspace-tabs {
  flex: 1;
  min-height: 0;
}
</style>
