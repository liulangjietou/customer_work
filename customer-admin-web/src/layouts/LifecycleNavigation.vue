<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { InputInstance } from 'element-plus'
import { useMenuStore } from '@/store/menu'
import MenuTree from './MenuTree.vue'
import {
  buildNavigationSections,
  filterNavigationNodes,
  resolveNavigationSectionKey,
  type NavigationSectionKey,
} from './navigationModel'

const emit = defineEmits<{
  stateChange: [state: { compactViewport: boolean; overlayOpen: boolean; collapsed: boolean }]
  requestMainFocus: []
}>()

const route = useRoute()
const router = useRouter()
const menuStore = useMenuStore()
const navigationSections = computed(() => buildNavigationSections(menuStore.tree))
const selectedSectionKey = ref<NavigationSectionKey>('overview')
const contextQuery = ref('')
const compactViewport = ref(false)
const overlayOpen = ref(false)
const contextSearchInput = ref<InputInstance>()
let compactViewportQuery: MediaQueryList | null = null
let overlayTrigger: HTMLElement | null = null

// SQL 通用查询页靠 defineKey 区分菜单，高亮必须保留 query；普通页面仍按 path 对齐。
const activePath = computed(() => (route.path === '/sql/query' ? route.fullPath : route.path))
const isNavigationCompact = computed(() => menuStore.collapsed || compactViewport.value)
const asideWidth = computed(() => (
  isNavigationCompact.value ? 'var(--cw-nav-rail-width)' : 'var(--cw-shell-expanded-width)'
))
const currentSection = computed(() => (
  navigationSections.value.find((section) => section.key === selectedSectionKey.value)
  ?? navigationSections.value[0]
))
const filteredContextNodes = computed(() => (
  currentSection.value ? filterNavigationNodes(currentSection.value.menuNodes, contextQuery.value) : []
))

function syncSectionFromRoute() {
  const key = resolveNavigationSectionKey(navigationSections.value, route.fullPath, route.path)
  selectedSectionKey.value = navigationSections.value.some((section) => section.key === key)
    ? key
    : 'overview'
}

function focusContextSearch() {
  void nextTick(() => contextSearchInput.value?.focus())
}

function openOverlay(trigger: HTMLElement | null) {
  overlayTrigger = trigger
  overlayOpen.value = true
  focusContextSearch()
}

function closeOverlay(focusTarget: 'trigger' | 'main' | 'none' = 'trigger') {
  overlayOpen.value = false
  const trigger = overlayTrigger
  overlayTrigger = null
  if (focusTarget === 'trigger' && trigger?.isConnected) {
    void nextTick(() => trigger.focus())
  } else if (focusTarget === 'main') {
    void nextTick(() => emit('requestMainFocus'))
  }
}

// 路由变化与菜单热刷新都重新按真实树归属分区；从覆盖层完成导航时把焦点交给新页面。
watch(() => route.fullPath, () => {
  syncSectionFromRoute()
  contextQuery.value = ''
  if (overlayOpen.value) {
    closeOverlay('main')
  } else {
    overlayTrigger = null
  }
}, { immediate: true })
watch(() => menuStore.tree, syncSectionFromRoute)

watch(
  [compactViewport, overlayOpen, () => menuStore.collapsed],
  ([isCompactViewport, isOverlayOpen, isCollapsed]) => {
    emit('stateChange', {
      compactViewport: isCompactViewport,
      overlayOpen: isOverlayOpen,
      collapsed: isCollapsed,
    })
  },
  { immediate: true },
)

function selectSection(key: NavigationSectionKey, event: MouseEvent) {
  selectedSectionKey.value = key
  contextQuery.value = ''
  if (isNavigationCompact.value) {
    openOverlay(event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
  }
}

function toggleFromHeader(event: MouseEvent) {
  if (compactViewport.value) {
    if (overlayOpen.value) {
      closeOverlay()
    } else {
      openOverlay(event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
    }
    return
  }
  menuStore.toggleCollapsed()
  closeOverlay('none')
}

function updateCompactViewport(event: MediaQueryListEvent | MediaQueryList) {
  compactViewport.value = event.matches
  if (!event.matches) {
    closeOverlay('none')
  }
}

function handleEscape(event: KeyboardEvent) {
  if (!event.defaultPrevented && event.key === 'Escape' && overlayOpen.value) {
    event.preventDefault()
    closeOverlay()
  }
}

onMounted(() => {
  compactViewportQuery = window.matchMedia('(max-width: 1023px)')
  updateCompactViewport(compactViewportQuery)
  compactViewportQuery.addEventListener('change', updateCompactViewport)
  window.addEventListener('keydown', handleEscape)
})

onBeforeUnmount(() => {
  compactViewportQuery?.removeEventListener('change', updateCompactViewport)
  window.removeEventListener('keydown', handleEscape)
})

defineExpose({ toggleFromHeader })
</script>

<template>
  <el-aside
    :width="asideWidth"
    class="layout-aside"
    :class="{ 'is-compact': isNavigationCompact }"
  >
    <div class="primary-rail">
      <button
        class="rail-brand"
        type="button"
        title="返回首页"
        aria-label="返回首页"
        @click="router.push('/home')"
      >
        <span class="logo-mark">CW</span>
      </button>

      <nav class="rail-nav" aria-label="智能体生命周期导航">
        <button
          v-for="section in navigationSections"
          :key="section.key"
          type="button"
          class="rail-item"
          :class="{ 'is-active': selectedSectionKey === section.key }"
          :title="section.label"
          :aria-current="selectedSectionKey === section.key ? 'true' : undefined"
          aria-controls="lifecycle-context-menu"
          :aria-expanded="selectedSectionKey === section.key && (!isNavigationCompact || overlayOpen)"
          @click="selectSection(section.key, $event)"
        >
          <el-icon class="rail-icon"><component :is="section.icon" /></el-icon>
          <span class="rail-label">{{ section.label }}</span>
        </button>
      </nav>
    </div>

    <section
      v-show="!isNavigationCompact || overlayOpen"
      id="lifecycle-context-menu"
      class="context-pane"
      :class="{ 'is-overlay': isNavigationCompact }"
      :aria-label="currentSection ? `${currentSection.title}菜单` : '上下文菜单'"
    >
      <div v-if="currentSection" class="context-head">
        <div class="context-eyebrow">CUSTOMER WORK</div>
        <div class="context-title-row">
          <strong>{{ currentSection.title }}</strong>
          <span class="context-count">{{ currentSection.itemCount }}</span>
        </div>
        <el-button
          v-if="isNavigationCompact"
          class="context-close"
          text
          :icon="'Close'"
          aria-label="关闭上下文菜单"
          @click="closeOverlay()"
        />
      </div>

      <div v-if="currentSection" class="context-search-wrap">
        <el-input
          ref="contextSearchInput"
          v-model="contextQuery"
          class="context-search"
          :placeholder="currentSection.searchPlaceholder"
          clearable
          :aria-label="currentSection.searchPlaceholder"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <div class="context-scroll">
        <el-menu
          v-if="filteredContextNodes.length > 0"
          class="context-menu"
          :default-active="activePath"
          router
          unique-opened
        >
          <MenuTree :nodes="filteredContextNodes" />
        </el-menu>
        <el-empty v-else description="没有匹配的入口" :image-size="52" />
      </div>
    </section>
  </el-aside>

  <button
    v-if="isNavigationCompact && overlayOpen"
    type="button"
    class="navigation-backdrop"
    aria-label="关闭上下文菜单"
    @click="closeOverlay()"
  />
</template>

<style scoped>
.layout-aside {
  --cw-rail-bg: var(--cw-brand-ink);
  --cw-rail-hover: var(--cw-brand-ink-hover);
  height: 100%;
  display: flex;
  position: relative;
  z-index: 1200;
  overflow: visible;
  background: var(--cw-rail-bg);
  border-right: 1px solid var(--el-border-color-lighter);
  transition: width 180ms ease-out;
}

.primary-rail {
  width: var(--cw-nav-rail-width);
  height: 100%;
  flex: 0 0 var(--cw-nav-rail-width);
  display: flex;
  flex-direction: column;
  background: var(--cw-rail-bg);
  color: #b7c5d8;
}

.rail-brand {
  width: var(--cw-nav-rail-width);
  height: var(--cw-topbar-height);
  flex: 0 0 var(--cw-topbar-height);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
  background: transparent;
  cursor: pointer;
}

.logo-mark {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: linear-gradient(145deg, var(--cw-brand-logo-start), var(--cw-brand-logo-end));
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.04em;
  box-shadow: 0 7px 18px rgb(58 86 210 / 32%);
}

.rail-nav {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 8px;
  overflow-y: auto;
}

.rail-item {
  width: 56px;
  min-height: 55px;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 7px 4px 6px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #91a1b7;
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease;
}

.rail-item:hover {
  background: var(--cw-rail-hover);
  color: #e6edf7;
}

.rail-item.is-active {
  background: rgb(79 110 247 / 17%);
  color: #fff;
}

.rail-item.is-active::after {
  content: '';
  width: 3px;
  height: 24px;
  position: absolute;
  right: -8px;
  top: 50%;
  border-radius: 3px 0 0 3px;
  background: var(--cw-brand-signal);
  transform: translateY(-50%);
}

.rail-icon {
  font-size: 19px;
}

.rail-label {
  font-size: 11px;
  line-height: 16px;
  letter-spacing: 0.02em;
}

.context-pane {
  width: var(--cw-context-nav-width);
  min-width: var(--cw-context-nav-width);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  box-shadow: 1px 0 0 var(--el-border-color-lighter);
}

.context-pane.is-overlay {
  width: 220px;
  min-width: 220px;
  position: absolute;
  inset: 0 auto 0 var(--cw-nav-rail-width);
  z-index: 2;
  box-shadow: 14px 0 36px rgb(15 23 42 / 18%);
}

.context-head {
  min-height: var(--cw-topbar-height);
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 0 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.context-eyebrow {
  color: var(--el-text-color-placeholder);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.context-title-row {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
}

.context-title-row strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 650;
}

.context-count {
  min-width: 20px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 10px;
}

.context-close {
  position: absolute;
  top: 16px;
  right: 8px;
}

.context-search-wrap {
  padding: 12px 10px 8px;
}

.context-search :deep(.el-input__wrapper) {
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  box-shadow: 0 0 0 1px var(--el-border-color-lighter) inset;
}

.context-search :deep(.el-input__inner) {
  font-size: 12px;
}

.context-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 2px 8px 14px;
}

.context-menu {
  border-right: 0;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--el-fill-color-light);
  --el-menu-active-color: var(--el-text-color-primary);
  --el-menu-text-color: var(--el-text-color-regular);
}

.context-menu :deep(.el-menu-item),
.context-menu :deep(.el-sub-menu__title) {
  min-width: 0;
  height: 40px;
  position: relative;
  margin: 2px 0;
  border-radius: 8px;
  line-height: 40px;
  font-size: 13px;
}

.context-menu :deep(.el-menu-item .el-icon),
.context-menu :deep(.el-sub-menu__title .el-icon) {
  color: var(--el-text-color-secondary) !important;
}

.context-menu :deep(.el-menu-item span),
.context-menu :deep(.el-sub-menu__title span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.context-menu :deep(.el-menu-item.is-active::before) {
  content: '';
  width: 2px;
  height: 22px;
  position: absolute;
  left: 0;
  top: 50%;
  border-radius: 0 2px 2px 0;
  background: var(--theme-primary, var(--el-color-primary));
  transform: translateY(-50%);
}

.navigation-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1180;
  padding: 0;
  border: 0;
  background: rgb(15 23 42 / 18%);
  cursor: default;
}

@media (prefers-reduced-motion: reduce) {
  .layout-aside,
  .rail-item {
    transition: none;
  }
}
</style>
