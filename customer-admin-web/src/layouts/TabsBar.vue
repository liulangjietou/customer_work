<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
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

// 右键上下文菜单：定位到具体被右键的标签靠事件委托 + DOM 顺序对齐 tabsStore.tabs（Element Plus
// 的 el-tab-pane 不是实际渲染节点，拿不到 paneName，只能反查 .el-tabs__item 在导航条里的下标）。
const menuVisible = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuTargetKey = ref('')

function onTabsContextMenu(event: MouseEvent) {
  const item = (event.target as HTMLElement).closest('.el-tabs__item') as HTMLElement | null
  if (!item) {
    return
  }
  event.preventDefault()
  const nav = item.parentElement
  const items = nav ? Array.from(nav.querySelectorAll('.el-tabs__item')) : []
  const index = items.indexOf(item)
  const tab = tabsStore.tabs[index]
  if (!tab) {
    return
  }
  menuTargetKey.value = tab.key
  menuX.value = event.clientX
  menuY.value = event.clientY
  menuVisible.value = true
}

function closeMenu() {
  menuVisible.value = false
}

function doClose() {
  tabsStore.closeTab(menuTargetKey.value)
  closeMenu()
}

function doCloseOthers() {
  tabsStore.closeOthers(menuTargetKey.value)
  closeMenu()
}

function doCloseToRight() {
  tabsStore.closeToRight(menuTargetKey.value)
  closeMenu()
}

const targetTab = () => tabsStore.tabs.find((t) => t.key === menuTargetKey.value)
const canClose = () => targetTab()?.closable ?? false
const canCloseOthers = () => tabsStore.tabs.some((t) => t.closable && t.key !== menuTargetKey.value)
const canCloseToRight = () => {
  const idx = tabsStore.tabs.findIndex((t) => t.key === menuTargetKey.value)
  return idx !== -1 && idx < tabsStore.tabs.length - 1
}

onMounted(() => window.addEventListener('click', closeMenu))
onBeforeUnmount(() => window.removeEventListener('click', closeMenu))
</script>

<template>
  <div class="tabs-bar-wrapper" @contextmenu="onTabsContextMenu">
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

    <ul
      v-if="menuVisible"
      class="tab-context-menu"
      :style="{ left: menuX + 'px', top: menuY + 'px' }"
      @click.stop
    >
      <li :class="{ disabled: !canClose() }" @click="canClose() && doClose()">关闭</li>
      <li :class="{ disabled: !canCloseOthers() }" @click="canCloseOthers() && doCloseOthers()">关闭其他</li>
      <li :class="{ disabled: !canCloseToRight() }" @click="canCloseToRight() && doCloseToRight()">关闭右侧</li>
    </ul>
  </div>
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

.tabs-bar-wrapper {
  position: relative;
}

.tab-context-menu {
  position: fixed;
  z-index: 2000;
  min-width: 100px;
  padding: 4px 0;
  margin: 0;
  list-style: none;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgb(0 0 0 / 10%);
}

.tab-context-menu li {
  padding: 6px 16px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  white-space: nowrap;
}

.tab-context-menu li:hover {
  background: #f5f7fa;
  color: #409eff;
}

.tab-context-menu li.disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.tab-context-menu li.disabled:hover {
  background: transparent;
  color: #c0c4cc;
}
</style>
