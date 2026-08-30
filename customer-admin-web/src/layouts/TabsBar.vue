<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useTabsStore } from '@/store/tabs'
import type { TabsPaneContext, TabPaneName } from 'element-plus'

const router = useRouter()
const tabsStore = useTabsStore()

function handleTabClick(pane: TabsPaneContext) {
  const key = pane.paneName
  const tab = tabsStore.tabs.find((item) => item.key === key)
  if (tab) {
    void router.push(tab.fullPath)
  }
}

function handleTabRemove(paneName: TabPaneName) {
  tabsStore.closeTab(String(paneName))
}

const menuVisible = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuTargetKey = ref('')
const contextMenu = ref<HTMLElement>()
let contextTriggerItem: HTMLElement | null = null
type MenuFocusTarget = 'none' | 'trigger' | 'active'

function tabKeyFromItem(item: HTMLElement): string | undefined {
  return item.querySelector<HTMLElement>('[data-tab-key]')?.dataset.tabKey
}

function openContextMenu(item: HTMLElement, x: number, y: number) {
  const key = tabKeyFromItem(item)
  if (!key || !tabsStore.tabs.some((tab) => tab.key === key)) return
  menuTargetKey.value = key
  menuX.value = Math.min(x, window.innerWidth - 150)
  menuY.value = Math.min(y, window.innerHeight - 132)
  contextTriggerItem = item
  menuVisible.value = true
  void nextTick(() => contextMenu.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus())
}

function onTabsContextMenu(event: MouseEvent) {
  const item = (event.target as HTMLElement).closest('.el-tabs__item') as HTMLElement | null
  if (!item) return
  event.preventDefault()
  openContextMenu(item, event.clientX, event.clientY)
}

function onTabsKeydown(event: KeyboardEvent) {
  if (!(event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10'))) return
  const item = (event.target as HTMLElement).closest('.el-tabs__item') as HTMLElement | null
  if (!item) return
  event.preventDefault()
  const rect = item.getBoundingClientRect()
  openContextMenu(item, rect.left, rect.bottom + 4)
}

function findTabItem(key: string): HTMLElement | undefined {
  return Array.from(document.querySelectorAll<HTMLElement>('.el-tabs__item'))
    .find((item) => tabKeyFromItem(item) === key)
}

function focusTab(key: string) {
  void nextTick(() => findTabItem(key)?.focus())
}

function closeMenu(focusTarget: MenuFocusTarget = 'none') {
  menuVisible.value = false
  const trigger = contextTriggerItem
  contextTriggerItem = null
  if (focusTarget === 'trigger' && trigger?.isConnected) {
    void nextTick(() => trigger.focus())
  } else if (focusTarget === 'active') {
    focusTab(tabsStore.activeKey)
  }
}

function handleWindowDismiss() {
  closeMenu()
}

function handleMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu('trigger')
    return
  }

  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = Array.from(
    contextMenu.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [],
  )
  if (items.length === 0) return
  event.preventDefault()
  const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement)
  const nextIndex = event.key === 'Home'
    ? 0
    : event.key === 'End'
      ? items.length - 1
      : event.key === 'ArrowUp'
        ? (currentIndex <= 0 ? items.length - 1 : currentIndex - 1)
        : (currentIndex + 1) % items.length
  items[nextIndex]?.focus()
}

function handleMenuFocusout(event: FocusEvent) {
  const nextTarget = event.relatedTarget
  if (!(nextTarget instanceof Node) || !contextMenu.value?.contains(nextTarget)) {
    closeMenu()
  }
}

function doClose() {
  tabsStore.closeTab(menuTargetKey.value)
  closeMenu('active')
}

function doCloseOthers() {
  tabsStore.closeOthers(menuTargetKey.value)
  closeMenu('trigger')
}

function doCloseToRight() {
  tabsStore.closeToRight(menuTargetKey.value)
  closeMenu('trigger')
}

const targetTab = () => tabsStore.tabs.find((tab) => tab.key === menuTargetKey.value)
const canClose = () => targetTab()?.closable ?? false
const canCloseOthers = () => tabsStore.tabs.some((tab) => tab.closable && tab.key !== menuTargetKey.value)
const canCloseToRight = () => {
  const index = tabsStore.tabs.findIndex((tab) => tab.key === menuTargetKey.value)
  return index !== -1 && index < tabsStore.tabs.length - 1
}

onMounted(() => {
  window.addEventListener('click', handleWindowDismiss)
  window.addEventListener('resize', handleWindowDismiss)
  window.addEventListener('blur', handleWindowDismiss)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', handleWindowDismiss)
  window.removeEventListener('resize', handleWindowDismiss)
  window.removeEventListener('blur', handleWindowDismiss)
})
</script>

<template>
  <div
    class="tabs-bar-wrapper"
    @contextmenu="onTabsContextMenu"
    @keydown="onTabsKeydown"
  >
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
        :closable="tab.closable"
      >
        <template #label>
          <span class="tab-label" :data-tab-key="tab.key">{{ tab.title }}</span>
        </template>
      </el-tab-pane>
    </el-tabs>

    <div
      v-if="menuVisible"
      ref="contextMenu"
      class="tab-context-menu"
      role="menu"
      aria-label="页签操作"
      :style="{ left: `${menuX}px`, top: `${menuY}px` }"
      @click.stop
      @keydown="handleMenuKeydown"
      @focusout="handleMenuFocusout"
    >
      <button type="button" role="menuitem" :disabled="!canClose()" @click="doClose">关闭</button>
      <button type="button" role="menuitem" :disabled="!canCloseOthers()" @click="doCloseOthers">关闭其他</button>
      <button type="button" role="menuitem" :disabled="!canCloseToRight()" @click="doCloseToRight">关闭右侧</button>
    </div>
  </div>
</template>

<style scoped>
.tabs-bar-wrapper {
  height: var(--cw-tabs-height);
  position: relative;
  flex: 0 0 var(--cw-tabs-height);
  overflow: hidden;
  border-bottom: 1px solid var(--cw-line, var(--el-border-color-lighter));
  background: var(--cw-paper, var(--el-bg-color));
}

.tabs-bar {
  height: var(--cw-tabs-height);
  padding: 3px 12px 0;
  background: var(--cw-paper, var(--el-bg-color));
}

.tabs-bar :deep(.el-tabs__header) {
  height: 34px;
  margin: 0;
  border-bottom: 0;
}

.tabs-bar :deep(.el-tabs__nav-wrap),
.tabs-bar :deep(.el-tabs__nav-scroll),
.tabs-bar :deep(.el-tabs__nav) {
  height: 34px;
}

.tabs-bar :deep(.el-tabs__nav) {
  border: 0;
  gap: 2px;
}

.tabs-bar :deep(.el-tabs__item) {
  height: 34px;
  position: relative;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: var(--cw-radius-sm, 6px) var(--cw-radius-sm, 6px) 0 0;
  background: transparent;
  color: var(--cw-text-muted, var(--el-text-color-secondary));
  line-height: 34px;
  font-size: 12px;
  transition: background-color 140ms ease, color 140ms ease;
}

.tabs-bar :deep(.el-tabs__item + .el-tabs__item) {
  border-left: 1px solid transparent;
}

.tabs-bar :deep(.el-tabs__item:hover) {
  background: var(--cw-canvas, var(--el-fill-color-light));
  color: var(--cw-text, var(--el-text-color-primary));
}

.tabs-bar :deep(.el-tabs__item.is-active) {
  border-color: var(--cw-line, var(--el-border-color-lighter));
  border-bottom-color: var(--cw-paper, var(--el-bg-color));
  background: var(--cw-paper, var(--el-bg-color));
  color: var(--cw-cobalt, var(--el-color-primary));
  font-weight: 650;
}

.tabs-bar :deep(.el-tabs__item.is-active::before) {
  content: '';
  height: 2px;
  position: absolute;
  inset: -1px 7px auto;
  border-radius: 0 0 2px 2px;
  background: var(--cw-cobalt, var(--el-color-primary));
}

.tabs-bar :deep(.el-tabs__item .is-icon-close) {
  opacity: 0;
  transition: opacity 120ms ease;
}

.tabs-bar :deep(.el-tabs__item:hover .is-icon-close),
.tabs-bar :deep(.el-tabs__item.is-active .is-icon-close),
.tabs-bar :deep(.el-tabs__item:focus-visible .is-icon-close) {
  opacity: 1;
}

.tab-label {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}

.tab-context-menu {
  min-width: 132px;
  position: fixed;
  z-index: 2200;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px;
  border: 1px solid var(--cw-line, var(--el-border-color-lighter));
  border-radius: var(--cw-radius-md, 8px);
  background: var(--cw-paper, var(--el-bg-color-overlay));
  box-shadow: var(--cw-shadow-sm, 0 12px 30px rgb(15 23 42 / 16%));
}

.tab-context-menu button {
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--cw-radius-sm, 6px);
  background: transparent;
  color: var(--cw-text-muted, var(--el-text-color-regular));
  text-align: left;
  font-size: 12px;
  cursor: pointer;
}

.tab-context-menu button:hover:not(:disabled),
.tab-context-menu button:focus-visible:not(:disabled) {
  background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 9%, var(--cw-paper, var(--el-bg-color)));
  color: var(--cw-cobalt, var(--el-color-primary));
}

.tab-context-menu button:disabled {
  color: var(--cw-text-muted, var(--el-text-color-placeholder));
  opacity: 0.55;
  cursor: not-allowed;
}

@media (max-width: 760px) {
  .tabs-bar {
    padding-right: 4px;
    padding-left: 4px;
  }

  .tabs-bar :deep(.el-tabs__nav) {
    gap: 1px;
  }

  .tabs-bar :deep(.el-tabs__item) {
    padding: 0 9px;
  }

  .tab-label {
    max-width: 116px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .tabs-bar :deep(.el-tabs__item),
  .tabs-bar :deep(.el-tabs__item .is-icon-close) {
    transition: none;
  }
}
</style>
