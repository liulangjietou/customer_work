<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import MenuTree from './MenuTree.vue'
import TabsBar from './TabsBar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
import FooterCopyright from '@/components/FooterCopyright.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()

const activePath = computed(() => route.path)
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
      <el-menu
        :default-active="activePath"
        :collapse="menuStore.collapsed"
        :collapse-transition="true"
        router
        unique-opened
        background-color="#001529"
        text-color="#c9d1d9"
        active-text-color="#fff"
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
        <el-dropdown>
          <span class="user-info">{{ auth.nickname }}<el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="router.push({ name: 'ChangePassword' })">修改密码</el-dropdown-item>
              <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <TabsBar />
      <AppBreadcrumb />
      <el-main class="layout-main">
        <router-view />
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
  background: #001529;
  display: flex;
  flex-direction: column;
  transition: width 0.28s ease-in-out;
  overflow-x: hidden;
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
  border-bottom: 1px solid #eee;
  padding: 0 16px;
}

.header-left {
  display: flex;
  align-items: center;
}

.collapse-btn {
  font-size: 18px;
  color: #606266;
  padding: 8px;
}

.collapse-btn:hover {
  color: #409eff;
  background: #f5f7fa;
}

.user-info {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.layout-main {
  background: #f5f7fa;
}

.layout-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border-top: 1px solid #eee;
  padding: 0;
}
</style>
