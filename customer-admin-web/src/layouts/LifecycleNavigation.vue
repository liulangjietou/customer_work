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
// 首帧就按真实视口计算，避免窄屏先渲染完整侧栏、挂载后再突然收起。
const compactViewport = ref(
  typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(COMPACT_VIEWPORT_QUERY).matches,
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
const asideWidth = computed(() =>
  compactViewport.value
    ? '0px'
    : isDesktopCollapsed.value
      ? 'var(--cw-nav-rail-width)'
      : 'var(--cw-shell-expanded-width)',
)
const currentSection = computed(
  () =>
    navigationSections.value.find((section) => section.key === selectedSectionKey.value) ??
    navigationSections.value[0],
)
const filteredContextNodes = computed(() =>
  currentSection.value
    ? filterNavigationNodes(currentSection.value.menuNodes, contextQuery.value)
    : [],
)

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
watch(
  () => route.fullPath,
  () => {
    syncSectionFromRoute()
    contextQuery.value = ''
    if (overlayOpen.value) {
      closeOverlay('main')
    } else {
      overlayTrigger = null
    }
  },
  { immediate: true },
)
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
  const focusable = Array.from(
    navigationShell.value?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], input:not(:disabled), [tabindex]:not([tabindex="-1"])',
    ) ?? [],
  ).filter((element) => element.offsetParent !== null)
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
          <span class="brand-copy"
            ><strong>Customer Work</strong><small>企业智能体工作台</small></span
          >
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
            :aria-expanded="
              selectedSectionKey === section.key && (!isNavigationCompact || overlayOpen)
            "
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
            <template #prefix
              ><el-icon><Search /></el-icon
            ></template>
          </el-input>
        </div>

        <div
          class="context-scroll"
          :class="{ 'has-history': selectedSectionKey === 'agents' && route.name === 'Workspace' }"
        >
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
        <!-- 工作区负责会话数据；导航仅提供稳定挂载位置，避免另建一份历史状态。 -->
        <div
          id="workspace-history-slot"
          v-show="selectedSectionKey === 'agents' && route.name === 'Workspace'"
          class="workspace-history-slot"
        />
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
  z-index: 1201;
  flex: 0 0 auto;
  overflow: visible;
  transition: width 180ms ease;
}
.navigation-shell {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--cw-canvas);
  border-right: 1px solid var(--cw-line);
}
.primary-rail {
  flex: 0 0 auto;
  padding: 0 12px 12px;
}
.rail-brand {
  width: 100%;
  height: 72px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  color: var(--cw-text);
  text-align: left;
  cursor: pointer;
  padding: 0 4px;
}
.logo-mark {
  flex: 0 0 32px;
  height: 32px;
  display: grid;
  place-items: center;
  background: var(--cw-cobalt-solid);
  color: var(--cw-on-primary);
  border-radius: 9px;
  font-size: 12px;
  font-weight: 750;
  letter-spacing: -1px;
}
.brand-copy {
  display: grid;
  gap: 4px;
  white-space: nowrap;
}
.brand-copy strong {
  font-size: 15px;
  letter-spacing: -0.3px;
}
.brand-copy small {
  color: var(--cw-text-muted);
  font-size: 11px;
}
.rail-nav {
  display: grid;
  gap: 3px;
}
.rail-item {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 39px;
  width: 100%;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--cw-text-muted);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  text-align: left;
}
.rail-icon {
  font-size: 18px;
  flex: 0 0 auto;
}
.rail-item:hover {
  background: var(--el-fill-color);
  color: var(--cw-text);
}
.rail-item.is-active {
  background: color-mix(in srgb, var(--cw-cobalt) 9%, var(--cw-paper));
  color: var(--cw-cobalt);
  font-weight: 650;
}
.context-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin: 0 12px;
  border-top: 1px solid var(--cw-line);
}
.context-head {
  position: relative;
  padding: 16px 6px 12px;
  flex: 0 0 auto;
}
.context-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--cw-text-muted);
  font-size: 12px;
}
.context-title-row strong {
  font-weight: 550;
}
.context-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.context-close {
  position: absolute;
  top: 7px;
  right: 0;
}
.context-pane.is-overlay .context-count,
.is-mobile .context-count {
  margin-right: 28px;
}
.context-search-wrap {
  padding-bottom: 8px;
}
.context-search :deep(.el-input__wrapper) {
  background: var(--cw-paper);
  box-shadow: none;
  border: 1px solid var(--cw-line);
  padding: 0 9px;
}
.context-search :deep(.el-input__inner) {
  font-size: 12px;
  height: 30px;
}
.context-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding-bottom: 12px;
}
.context-scroll.has-history {
  flex: 0 1 auto;
  max-height: 180px;
}
.context-menu {
  border: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--cw-text-muted);
  --el-menu-hover-bg-color: var(--el-fill-color);
  --el-menu-active-color: var(--cw-cobalt);
  --el-menu-item-height: 36px;
  --el-menu-sub-item-height: 34px;
}
.context-menu :deep(.el-menu-item),
.context-menu :deep(.el-sub-menu__title) {
  font-size: 12px;
  border-radius: 6px;
  margin-bottom: 2px;
  padding-right: 9px;
}
.context-menu :deep(.el-menu-item.is-active) {
  color: var(--cw-cobalt);
  background: color-mix(in srgb, var(--cw-cobalt) 8%, var(--cw-paper));
  font-weight: 600;
}
.context-menu :deep(.el-menu-item .el-icon),
.context-menu :deep(.el-sub-menu__title .el-icon) {
  font-size: 16px;
  width: 18px;
  margin-right: 7px;
}
.workspace-history-slot {
  flex: 1;
  min-height: 140px;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--cw-line);
}
.is-desktop-collapsed .primary-rail {
  padding: 0 8px;
}
.is-desktop-collapsed .brand-copy,
.is-desktop-collapsed .rail-label {
  display: none;
}
.is-desktop-collapsed .rail-brand {
  justify-content: center;
}
.is-desktop-collapsed .rail-item {
  justify-content: center;
  padding: 0;
  height: 42px;
}
.context-pane.is-overlay {
  position: fixed;
  top: 0;
  bottom: 0;
  left: var(--cw-nav-rail-width);
  width: 240px;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--cw-line);
  background: var(--cw-canvas);
  box-shadow: var(--cw-shadow-lg);
}
.navigation-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1200;
  border: 0;
  background: rgb(12 22 40 / 32%);
}
.is-mobile .navigation-shell {
  display: none;
  position: fixed;
  inset: 0 auto 0 0;
  width: min(300px, calc(100vw - 48px));
  box-shadow: var(--cw-shadow-lg);
}
.is-mobile-open .navigation-shell {
  display: flex;
}
.is-mobile .rail-item {
  height: 40px;
}
@media (prefers-reduced-motion: reduce) {
  .layout-aside {
    transition: none;
  }
}
</style>
