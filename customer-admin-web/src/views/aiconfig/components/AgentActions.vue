<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/store/auth'
import type { AgentVO } from '@/types/api'

const props = defineProps<{ agent: AgentVO; canOpen: boolean; prominent?: boolean }>()
defineEmits<{ open: []; edit: []; toggle: []; memory: []; delete: [] }>()
const auth = useAuthStore()
const canReadMemory = computed(
  () => auth.hasPermission('agent:view') && props.agent.capabilities?.includes('memory'),
)
const hasMoreActions = computed(
  () =>
    auth.hasPermission('agent:edit') || auth.hasPermission('agent:delete') || canReadMemory.value,
)
</script>

<template>
  <div class="agent-actions">
    <el-button v-if="canOpen" :link="!prominent" type="primary" @click="$emit('open')"
      >打开</el-button
    >
    <el-button
      v-if="auth.hasPermission('agent:edit')"
      :link="!prominent"
      :type="prominent ? '' : 'primary'"
      @click="$emit('edit')"
      >配置</el-button
    >
    <el-dropdown v-if="hasMoreActions" trigger="click">
      <el-button text :aria-label="`${agent.agentName}的更多操作`"
        ><el-icon><MoreFilled /></el-icon
      ></el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-if="auth.hasPermission('agent:edit')" @click="$emit('toggle')">{{
            agent.status === 1 ? '停用智能体' : '启用智能体'
          }}</el-dropdown-item>
          <el-dropdown-item v-if="canReadMemory" @click="$emit('memory')"
            >查看长期记忆</el-dropdown-item
          >
          <el-dropdown-item
            v-if="auth.hasPermission('agent:delete')"
            :divided="auth.hasPermission('agent:edit') || canReadMemory"
            class="danger-action"
            @click="$emit('delete')"
            >删除智能体</el-dropdown-item
          >
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.agent-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.agent-actions > .el-button + .el-button {
  margin-left: 0;
}
.agent-actions .el-dropdown {
  margin-left: auto;
}
.danger-action {
  color: var(--cw-danger);
}
</style>
