<script setup lang="ts">
import type { MenuNode } from '@/types/api'

defineProps<{ nodes: MenuNode[]; collapsed?: boolean }>()

/** 动态智能体 id 与权限 id 来自两张表，视觉扁平后可能碰撞，key 必须带来源域。 */
function nodeKey(node: MenuNode): string {
  return node.dynamic
    ? `agent:${node.agentCode || node.id}`
    : `menu:${node.id}`
}
</script>

<template>
  <template v-for="node in nodes" :key="nodeKey(node)">
    <el-sub-menu
      v-if="node.children && node.children.length > 0"
      :index="node.path || String(node.id)"
      :title="node.name"
    >
      <template #title>
        <img v-if="node.iconType === 'image' && node.icon" :src="node.icon" alt="" class="menu-icon-img" />
        <el-icon v-else><component :is="node.icon || 'Folder'" /></el-icon>
        <span v-show="!collapsed">{{ node.name }}</span>
      </template>
      <MenuTree :nodes="node.children" :collapsed="collapsed" />
    </el-sub-menu>
    <el-menu-item
      v-else
      :index="node.path || String(node.id)"
      :title="node.name"
    >
      <img v-if="node.iconType === 'image' && node.icon" :src="node.icon" alt="" class="menu-icon-img" />
      <el-icon v-else><component :is="node.icon || 'Document'" /></el-icon>
      <span v-show="!collapsed">{{ node.name }}</span>
    </el-menu-item>
  </template>
</template>

<style scoped>
/* el-menu 自带的图标间距规则按 .el-icon 类选择器命中，<img> 标签享受不到，手动补一份保持视觉一致。 */
.menu-icon-img {
  width: 18px;
  height: 18px;
  margin-right: 5px;
  object-fit: contain;
  vertical-align: middle;
}

/* 折叠模式下让图标居中，移除图片右侧多余间距 */
:deep(.el-menu--collapse) .menu-icon-img {
  margin-right: 0;
}
</style>
