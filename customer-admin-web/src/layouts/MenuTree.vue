<script setup lang="ts">
import type { MenuNode } from '@/types/api'

defineProps<{ nodes: MenuNode[] }>()

// 按 perm_code 给系统管理 / AI 配置的叶子菜单个性化配色（图标名本身来自后端 sys_permission.icon
// 种子数据，见 V5__menu_icons.sql）；未命中的节点（分组节点、动态智能体节点）不着色，继承菜单默认
// 文字颜色，不强行统一风格。
const ICON_COLORS: Record<string, string> = {
  user: '#409EFF',
  role: '#9B59B6',
  log: '#E6A23C',
  model: '#13C2C2',
  mcp: '#6554C0',
  skill: '#52C41A',
  agent: '#EB2F96',
}

function iconColor(permCode: string | null): string | undefined {
  return permCode ? ICON_COLORS[permCode] : undefined
}
</script>

<template>
  <template v-for="node in nodes" :key="node.id">
    <el-sub-menu v-if="node.children && node.children.length > 0" :index="node.path || String(node.id)">
      <template #title>
        <el-icon :style="{ color: iconColor(node.permCode) }"><component :is="node.icon || 'Folder'" /></el-icon>
        <span>{{ node.name }}</span>
      </template>
      <MenuTree :nodes="node.children" />
    </el-sub-menu>
    <el-menu-item v-else :index="node.path || String(node.id)">
      <el-icon :style="{ color: iconColor(node.permCode) }"><component :is="node.icon || 'Document'" /></el-icon>
      <span>{{ node.name }}</span>
    </el-menu-item>
  </template>
</template>
