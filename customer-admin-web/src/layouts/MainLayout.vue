<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import { useThemeStore } from '@/store/theme'
import MenuTree from './MenuTree.vue'
import TabsBar from './TabsBar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import FooterCopyright from '@/components/FooterCopyright.vue'
import NotificationBell from '@/components/NotificationBell.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()
const themeStore = useThemeStore()

// SQL 通用查询页（/sql/query）是所有报表菜单共用的同一个路由 path，靠 defineKey 区分不同菜单项，
// 用 route.path 高亮时几十个报表菜单会全部一起高亮；此时改取 route.fullPath（含 query），
// 与菜单节点 node.path 里存的 /sql/query?defineKey=xxx 完整链接对齐，其它页面维持原样按 path 高亮。
const activePath = computed(() => (route.path === '/sql/query' ? route.fullPath : route.path))
const asideWidth = computed(() => menuStore.collapsed ? '64px' : '220px')

// MainLayout 只在 Layout 的子路由下挂载（登录页/改密页在其外层），路由一变化就落一个标签，
// 已存在的标签只切激活态、不重复追加。
watch(() => route.fullPath, () => tabsStore.openTab(route), { immediate: true })

async function handleLogout() {
  await auth.logout()
  menuStore.reset()
  tabsStore.reset()
  await router.replace({ name: 'Login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="asideWidth" class="layout-aside" :class="{ collapsed: menuStore.collapsed }">
      <div class="logo">
        <span class="logo-mark">CW</span>
        <span v-show="!menuStore.collapsed" class="logo-text">customer_work</span>
      </div>
      <!-- 深色品牌侧边栏：配色走 .layout-aside 上的 CSS 变量（不用 props 传色，props 方式
           已被 EP 标记废弃且无法参与明暗切换的变量级联） -->
      <el-menu
        :default-active="activePath"
        :collapse="menuStore.collapsed"
        :collapse-transition="true"
        router
        unique-opened
      >
        <MenuTree :nodes="menuStore.tree" :collapsed="menuStore.collapsed" />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-button
            class="collapse-btn"
            text
            :icon="menuStore.collapsed ? 'Expand' : 'Fold'"
            @click="menuStore.toggleCollapsed"
          />
        </div>
        <div class="header-right">
          <el-button
            class="collapse-btn"
            text
            :icon="themeStore.isDark ? 'Sunny' : 'Moon'"
            :title="themeStore.isDark ? '切换到亮色模式' : '切换到暗色模式'"
            @click="themeStore.toggleDark()"
          />
          <NotificationBell />
          <el-dropdown>
            <span class="user-info">{{ auth.nickname }}<el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push({ name: 'ChangePassword' })">修改密码</el-dropdown-item>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <TabsBar />
      <AppBreadcrumb />
      <el-main class="layout-main">
        <!-- 只精确缓存 WorkspaceView：TabsBar 让"已打开的标签"在视觉上常驻，但底下的路由组件此前没配
             keep-alive，标签切走再切回其实是整个组件重新 mount——WorkspaceView 里的对话/VibeCoding
             面板state 全在组件本地 ref，一销毁就清空，进行中的 SSE 流也被 onUnmounted 里的 abortStream
             打断。这里不做成全局 include（tabsStore.tabs 覆盖的其它页面，如表格/表单页，未必对"数据
             保持不刷新重进"这件事做过设计，贸然全量 keep-alive 影响面不可控），只精确点名
             WorkspaceView，需要时再按同样方式给其它页面加白名单。 -->
        <router-view v-slot="{ Component }">
          <keep-alive :include="['WorkspaceView']">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
      <el-footer class="layout-footer" height="36px">
        <FooterCopyright />
      </el-footer>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout-aside {
  /* 品牌深色侧边栏：明暗两态保持同一深色，不随模式切换 */
  --cw-aside-bg: #001529;
  background: var(--cw-aside-bg);
  display: flex;
  flex-direction: column;
  transition: width 0.28s ease-in-out;
  overflow-x: hidden;
  /* el-menu 配色（替代已废弃的 background-color/text-color/active-text-color props） */
  --el-menu-bg-color: var(--cw-aside-bg);
  --el-menu-text-color: #c9d1d9;
  --el-menu-active-color: #fff;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.08);
  --el-menu-border-color: transparent;
}

.layout-aside.collapsed {
  --el-menu-icon-width: 24px;
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
  padding: 0 12px;
}

.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  flex-shrink: 0;
}

.logo-text {
  color: #fff;
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  transition: opacity 0.28s ease-in-out;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 0 16px;
}

.header-left {
  display: flex;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.collapse-btn {
  font-size: 18px;
  color: var(--el-text-color-regular);
  padding: 8px;
}

.collapse-btn:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.user-info {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.layout-main {
  background: var(--el-bg-color-page);
}

.layout-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 0;
}
</style>
