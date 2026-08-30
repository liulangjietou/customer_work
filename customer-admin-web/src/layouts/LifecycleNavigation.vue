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
const COMPACT_VIEWPORT_QUERY = '(max-width: 1023px)'
const navigationSections = computed(() => buildNavigationSections(menuStore.tree))
const selectedSectionKey = ref<NavigationSectionKey>('overview')
const contextQuery = ref('')
// 首帧就按真实视口计算，避免窄屏先渲染 272px 侧栏、挂载后再突然收起。
const compactViewport = ref(
  typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia(COMPACT_VIEWPORT_QUERY).matches,
)
const overlayOpen = ref(false)
const contextSearchInput = ref<InputInstance>()
const navigationShell = ref<HTMLElement>()
let compactViewportQuery: MediaQueryList | null = null
let overlayTrigger: HTMLElement | null = null

// SQL 通用查询页靠 defineKey 区分菜单，高亮必须保留 query；普通页面仍按 path 对齐。
const activePath = computed(() => (route.path === '/sql/query' ? route.fullPath : route.path))
const isNavigationCompact = computed(() => menuStore.collapsed || compactViewport.value)
const isDesktopCollapsed = computed(() => menuStore.collapsed && !compactViewport.value)
const asideWidth = computed(() => (
  compactViewport.value
    ? '0px'
    : isDesktopCollapsed.value
      ? 'var(--cw-nav-rail-width)'
      : 'var(--cw-shell-expanded-width)'
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
  // 移动端先由顶栏按钮打开完整抽屉，随后点击生命周期阶段时不能把焦点归还目标
  // 覆盖成抽屉内部按钮；否则关闭抽屉后焦点会落到不可见元素。
  if (!overlayOpen.value) {
    overlayTrigger = trigger
  }
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

function handleNavigationKeydown(event: KeyboardEvent) {
  if (!compactViewport.value || !overlayOpen.value || event.key !== 'Tab') return
  const focusable = Array.from(navigationShell.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), a[href], input:not(:disabled), [tabindex]:not([tabindex="-1"])',
  ) ?? []).filter((element) => element.offsetParent !== null)
  if (focusable.length === 0) return

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(() => {
  compactViewportQuery = window.matchMedia(COMPACT_VIEWPORT_QUERY)
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
    :class="{
      'is-compact': isNavigationCompact,
      'is-desktop-collapsed': isDesktopCollapsed,
      'is-mobile': compactViewport,
      'is-mobile-open': compactViewport && overlayOpen,
    }"
  >
    <div
      id="lifecycle-navigation-shell"
      ref="navigationShell"
      class="navigation-shell"
      :role="compactViewport && overlayOpen ? 'dialog' : undefined"
      :aria-modal="compactViewport && overlayOpen ? 'true' : undefined"
      :aria-label="compactViewport && overlayOpen ? '页面导航' : undefined"
      @keydown="handleNavigationKeydown"
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
        :class="{ 'is-overlay': isDesktopCollapsed }"
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
    </div>
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
  height: 100%;
  position: relative;
  z-index: 1200;
  flex: 0 0 auto;
  overflow: visible;
  background: transparent;
  transition: width 180ms ease-out;
}

.navigation-shell {
  width: 100%;
  height: 100%;
  position: relative;
  display: flex;
  background: var(--cw-ink, var(--cw-brand-ink));
}

.primary-rail {
  width: var(--cw-nav-rail-width);
  height: 100%;
  flex: 0 0 var(--cw-nav-rail-width);
  display: flex;
  flex-direction: column;
  background: var(--cw-ink, var(--cw-brand-ink));
  color: rgb(255 255 255 / 68%);
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
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 9px;
  background: linear-gradient(145deg, var(--cw-brand-logo-start), var(--cw-cobalt, var(--cw-brand-logo-end)));
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.05em;
  box-shadow: 0 7px 18px rgb(62 99 221 / 28%);
}

.rail-nav {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 11px 8px;
  overflow-y: auto;
  scrollbar-width: none;
}

.rail-nav::-webkit-scrollbar {
  display: none;
}

.rail-item {
  width: 48px;
  min-height: 48px;
  position: relative;
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 5px 2px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: rgb(255 255 255 / 58%);
  cursor: pointer;
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
}

.rail-item::before {
  width: 3px;
  height: 0;
  position: absolute;
  top: 50%;
  left: -7px;
  border-radius: 0 3px 3px 0;
  background: var(--cw-amber);
  content: '';
  transform: translateY(-50%);
  transition: height 180ms ease-out;
}

.rail-item:hover,
.rail-item.is-active {
  border-color: rgb(255 255 255 / 9%);
  background: rgb(255 255 255 / 9%);
  color: #fff;
}

.rail-item.is-active::before {
  height: 25px;
}

.rail-icon {
  font-size: 18px;
  line-height: 1;
}

.rail-label {
  font-size: 10px;
  line-height: 14px;
  letter-spacing: 0.02em;
}

.context-pane {
  width: var(--cw-context-nav-width);
  min-width: var(--cw-context-nav-width);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--cw-line, var(--el-border-color-lighter));
  background: var(--cw-paper, var(--el-bg-color));
  color: var(--cw-text, var(--el-text-color-primary));
}

.context-pane.is-overlay {
  position: absolute;
  inset: 0 auto 0 var(--cw-nav-rail-width);
  z-index: 2;
  box-shadow: var(--cw-shadow-lg, 14px 0 36px rgb(15 23 42 / 18%));
}

.context-head {
  min-height: var(--cw-topbar-height);
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  padding: 0 14px;
  border-bottom: 1px solid var(--cw-line, var(--el-border-color-lighter));
}

.context-eyebrow {
  color: var(--cw-cobalt, var(--el-color-primary));
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 0.13em;
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
  font-size: 14px;
  font-weight: 680;
}

.context-count {
  min-width: 20px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 5px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 9%, transparent);
  color: var(--cw-cobalt, var(--el-color-primary));
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 9px;
  font-weight: 700;
}

.context-close {
  width: 32px;
  height: 32px;
  position: absolute;
  top: 12px;
  right: 8px;
  padding: 0;
}

.context-search-wrap {
  padding: 11px 10px 8px;
}

.context-search :deep(.el-input__wrapper) {
  border-radius: var(--cw-radius-sm, 6px);
  background: var(--el-fill-color-extra-light);
  box-shadow: 0 0 0 1px var(--cw-line, var(--el-border-color-lighter)) inset;
}

.context-search :deep(.el-input__inner) {
  font-size: 12px;
}

.context-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 2px 8px 14px;
  scrollbar-color: var(--cw-line, var(--el-border-color)) transparent;
  scrollbar-width: thin;
}

.context-menu {
  border-right: 0;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--el-fill-color-light);
  --el-menu-active-color: var(--cw-cobalt, var(--el-color-primary));
  --el-menu-text-color: var(--el-text-color-regular);
}

.context-menu :deep(.el-menu-item),
.context-menu :deep(.el-sub-menu__title) {
  min-width: 0;
  height: 38px;
  position: relative;
  margin: 2px 0;
  border-radius: var(--cw-radius-sm, 6px);
  line-height: 38px;
  font-size: 12px;
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
  background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 10%, var(--cw-paper, var(--el-bg-color)));
  color: var(--cw-cobalt, var(--el-color-primary));
  font-weight: 650;
}

.context-menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--cw-cobalt, var(--el-color-primary)) !important;
}

.context-menu :deep(.el-menu-item.is-active::before) {
  width: 2px;
  height: 21px;
  position: absolute;
  top: 50%;
  left: 0;
  border-radius: 0 2px 2px 0;
  background: var(--cw-cobalt, var(--el-color-primary));
  content: '';
  transform: translateY(-50%);
}

.navigation-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1180;
  padding: 0;
  border: 0;
  background: rgb(5 13 30 / 52%);
  cursor: default;
}

.layout-aside.is-mobile .navigation-shell {
  width: min(var(--cw-shell-expanded-width), calc(100vw - 24px));
  position: fixed;
  inset: 0 auto 0 0;
  visibility: hidden;
  overflow: hidden;
  box-shadow: var(--cw-shadow-lg, 18px 0 48px rgb(5 13 30 / 26%));
  pointer-events: none;
  transform: translateX(-100%);
  transition: visibility 180ms step-end, transform 180ms ease-out;
}

.layout-aside.is-mobile-open .navigation-shell {
  visibility: visible;
  pointer-events: auto;
  transform: translateX(0);
  transition: transform 180ms ease-out;
}

.layout-aside.is-mobile .context-pane {
  width: calc(100% - var(--cw-nav-rail-width));
  min-width: 0;
  flex: 1 1 auto;
}

@media (prefers-reduced-motion: reduce) {
  .layout-aside,
  .navigation-shell,
  .rail-item,
  .rail-item::before {
    transition: none;
  }
}
</style>
