<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/store/tabs'
import type { TabsPaneContext, TabPaneName } from 'element-plus'

const router = useRouter()
const tabsStore = useTabsStore()

function handleTabClick(pane: TabsPaneContext) {
  const key = pane.paneName
  const tab = tabsStore.tabs.find((t) => t.key === key)
  if (tab) {
    router.push(tab.fullPath)
  }
}

function handleTabRemove(paneName: TabPaneName) {
  tabsStore.closeTab(String(paneName))
}
</script>

<template>
  <el-tabs
    v-model="tabsStore.activeKey"
    type="card"
    class="tabs-bar"
    @tab-click="handleTabClick"
    @tab-remove="handleTabRemove"
  >
    <el-tab-pane
      v-for="tab in tabsStore.tabs"
      :key="tab.key"
      :name="tab.key"
      :label="tab.title"
      :closable="tab.closable"
    />
  </el-tabs>
</template>

<style scoped>
.tabs-bar {
  padding: 8px 16px 0;
  background: #fff;
}

.tabs-bar :deep(.el-tabs__header) {
  margin: 0;
}

.tabs-bar :deep(.el-tabs__nav) {
  border: none;
}

.tabs-bar :deep(.el-tabs__item) {
  border: 1px solid #e4e7ed;
  border-radius: 4px 4px 0 0;
  margin-right: 4px;
  height: 32px;
  line-height: 32px;
}

.tabs-bar :deep(.el-tabs__item.is-active) {
  color: #409eff;
  font-weight: 600;
}
</style>
