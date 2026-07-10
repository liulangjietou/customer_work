<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import type { MenuNode } from '@/types/api'

const route = useRoute()
const menuStore = useMenuStore()

/** 按 path 精确匹配菜单树，返回从根到命中节点的名称链（分组名 + 叶子名）。 */
function findAncestorNames(nodes: MenuNode[], path: string, trail: string[]): string[] | null {
  for (const node of nodes) {
    const nextTrail = [...trail, node.name]
    if (node.path && node.path === path) {
      return nextTrail
    }
    if (node.children && node.children.length > 0) {
      const found = findAncestorNames(node.children, path, nextTrail)
      if (found) {
        return found
      }
    }
  }
  return null
}

const crumbs = computed(() => {
  const found = findAncestorNames(menuStore.tree, route.path, [])
  if (found) {
    return found
  }
  // 菜单树里查不到（首页、智能体工作区空态等纯前端路由），用路由自身标题兜底。
  const title = (route.meta.title as string | undefined) || (route.name ? String(route.name) : route.path)
  return [title]
})
</script>

<template>
  <el-breadcrumb class="app-breadcrumb" separator="/">
    <el-breadcrumb-item v-for="(name, idx) in crumbs" :key="idx">{{ name }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.app-breadcrumb {
  padding: 10px 16px;
  font-size: 13px;
}
</style>
