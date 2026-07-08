<script setup lang="ts">
import type { MenuNode } from '@/types/api'

defineProps<{ nodes: MenuNode[] }>()
</script>

<template>
  <template v-for="node in nodes" :key="node.id">
    <el-sub-menu v-if="node.children && node.children.length > 0" :index="node.path || String(node.id)">
      <template #title>
        <el-icon v-if="node.icon"><component :is="node.icon" /></el-icon>
        <span>{{ node.name }}</span>
      </template>
      <MenuTree :nodes="node.children" />
    </el-sub-menu>
    <el-menu-item v-else :index="node.path || String(node.id)">
      <el-icon v-if="node.icon"><component :is="node.icon" /></el-icon>
      <span>{{ node.name }}</span>
    </el-menu-item>
  </template>
</template>
