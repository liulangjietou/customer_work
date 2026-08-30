<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useTabsStore } from '@/store/tabs'
import LifecycleNavigation from './LifecycleNavigation.vue'
import AppHeader from './AppHeader.vue'
import TabsBar from './TabsBar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import PageContextHeader from './PageContextHeader.vue'
import { resolvePageTemplate } from './pagePresentation'
import FooterCopyright from '@/components/FooterCopyright.vue'

interface LifecycleNavigationHandle {
  toggleFromHeader: (event: MouseEvent) => void
}

const route = useRoute()
const auth = useAuthStore()
const tabsStore = useTabsStore()
const lifecycleNavigation = ref<LifecycleNavigationHandle>()
const mainContent = ref<{ $el: HTMLElement }>()
const navigationState = reactive({
  compactViewport: false,
  overlayOpen: false,
  collapsed: false,
})

// 工作区需要接管 el-main 的剩余高度来承载内部滚动；只按精确路由名收口，避免表格、表单页受影响。
const isWorkspaceRoute = computed(() => route.name === 'Workspace')
const pageTemplate = computed(() => resolvePageTemplate(route.path))
const shouldShowPageContext = computed(() => ![
  'Home',
  'Workspace',
  'WorkspaceEmpty',
  'ChangePassword',
  'NotFound',
].includes(String(route.name ?? '')))

// MainLayout 只负责壳层编排：路由变化落标签，导航归属与焦点生命周期由 LifecycleNavigation 自己维护。
watch(() => route.fullPath, () => tabsStore.openTab(route), { immediate: true })

function updateNavigationState(state: typeof navigationState) {
  Object.assign(navigationState, state)
}

function handleNavigationToggle(event: MouseEvent) {
  lifecycleNavigation.value?.toggleFromHeader(event)
}

function focusMainContent() {
  void nextTick(() => mainContent.value?.$el.focus())
}
</script>

<template>
  <el-container class="layout" direction="horizontal">
    <LifecycleNavigation
      ref="lifecycleNavigation"
      @state-change="updateNavigationState"
      @request-main-focus="focusMainContent"
    />

    <el-container class="layout-content" direction="vertical">
      <AppHeader
        :compact-viewport="navigationState.compactViewport"
        :overlay-open="navigationState.overlayOpen"
        :navigation-collapsed="navigationState.collapsed"
        @navigation-toggle="handleNavigationToggle"
      />
      <TabsBar v-if="auth.isApproved" />
      <AppBreadcrumb v-if="auth.isApproved" />
      <el-main
        ref="mainContent"
        class="layout-main"
        tabindex="-1"
        :class="{
          'layout-main--home': route.name === 'Home',
          'layout-main--workspace': isWorkspaceRoute,
        }"
        :data-page-template="pageTemplate"
      >
        <PageContextHeader v-if="shouldShowPageContext" />
        <!-- 只精确缓存 WorkspaceView：不要给 component 增加 fullPath key，也不要因主导航切换卸载
             router-view；否则切页会中断进行中的 SSE，并丢失对话或 VibeCoding 状态。 -->
        <router-view v-slot="{ Component }">
          <keep-alive :include="['WorkspaceView']">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
      <el-footer class="layout-footer" height="var(--cw-footer-height)">
        <FooterCopyright />
      </el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
  position: relative;
  overflow: hidden;
}

.layout-content {
  min-width: 0;
  min-height: 0;
}

.layout-main {
  min-width: 0;
  min-height: 0;
  background: var(--el-bg-color-page);
}

.layout-main:focus {
  outline: none;
}

.layout-main--home {
  padding: 0;
  overflow: hidden;
}

.layout-main--workspace {
  padding: 14px 18px 18px;
  overflow: hidden;
  background:
    radial-gradient(
      circle at 13% 0%,
      color-mix(in srgb, var(--theme-primary, var(--el-color-primary)) 5%, transparent),
      transparent 24%
    ),
    var(--el-bg-color-page);
}

.layout-footer {
  flex: 0 0 var(--cw-footer-height);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-lighter);
}

@media (max-width: 900px) {
  .layout-main--workspace {
    padding: 8px;
  }
}
</style>
