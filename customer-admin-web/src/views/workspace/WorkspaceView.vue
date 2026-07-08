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
        <ChatPanel :agent-code="agentCode" />
      </el-tab-pane>
      <el-tab-pane v-if="supportsVibeCoding" label="VibeCoding">
        <VibeCodingPanel :agent-code="agentCode" />
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
