<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useMenuStore } from '@/store/menu'
import { findMenuTrail } from './navigationModel'

const route = useRoute()
const menuStore = useMenuStore()

const crumbs = computed(() => {
  const found = findMenuTrail(menuStore.tree, route.fullPath, route.path)
  if (found) {
    return found
  }
  // 菜单树里查不到（首页、智能体工作区空态等纯前端路由），用路由自身标题兜底。
  const title =
    (route.meta.title as string | undefined) || (route.name ? String(route.name) : route.path)
  return [title]
})
</script>

<template>
  <el-breadcrumb class="app-breadcrumb" separator="/" aria-label="当前位置">
    <el-breadcrumb-item v-for="(name, idx) in crumbs" :key="idx">{{ name }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.app-breadcrumb {
  min-height: 30px;
  display: flex;
  align-items: center;
  padding: 0;
  overflow-x: auto;
  font-size: 13px;
  scrollbar-width: none;
  white-space: nowrap;
}

.app-breadcrumb::-webkit-scrollbar {
  display: none;
}

.app-breadcrumb :deep(.el-breadcrumb__inner) {
  color: var(--cw-text-muted, var(--el-text-color-secondary));
  font-weight: 500;
}

.app-breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--cw-text, var(--el-text-color-primary));
  font-weight: 650;
}

.app-breadcrumb :deep(.el-breadcrumb__separator) {
  color: var(--cw-text-muted, var(--el-text-color-placeholder));
  font-weight: 400;
  opacity: 0.65;
}

@media (max-width: 760px) {
  .app-breadcrumb {
    padding: 0 10px;
  }
}
</style>
