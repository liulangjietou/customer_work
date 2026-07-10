<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import MenuTree from './MenuTree.vue'
import TabsBar from './TabsBar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()

const activePath = computed(() => route.path)

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
    <el-aside width="220px" class="layout-aside">
      <div class="logo">
        <span class="logo-mark">CW</span>
        <span class="logo-text">customer_work</span>
      </div>
      <el-menu :default-active="activePath" router unique-opened background-color="#001529" text-color="#c9d1d9" active-text-color="#fff">
        <MenuTree :nodes="menuStore.tree" />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout-header">
        <span />
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
}

.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
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
}

.logo-text {
  color: #fff;
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eee;
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
</style>
