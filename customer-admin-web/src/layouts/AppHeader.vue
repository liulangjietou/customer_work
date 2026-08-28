<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import { useThemeStore } from '@/store/theme'
import GlobalCommandSearch from './GlobalCommandSearch.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import TenantSwitcher from '@/components/TenantSwitcher.vue'

const props = defineProps<{
  compactViewport: boolean
  overlayOpen: boolean
  navigationCollapsed: boolean
}>()
const emit = defineEmits<{ navigationToggle: [event: MouseEvent] }>()

const router = useRouter()
const auth = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()
const themeStore = useThemeStore()
const userInitial = computed(() => (
  (auth.nickname || auth.username || 'U').trim().slice(0, 1).toLocaleUpperCase()
))

const navigationToggleIcon = computed(() => {
  if (props.compactViewport) return props.overlayOpen ? 'Close' : 'Expand'
  return props.navigationCollapsed ? 'Expand' : 'Fold'
})
const navigationToggleLabel = computed(() => {
  if (props.compactViewport) return props.overlayOpen ? '关闭导航菜单' : '打开导航菜单'
  return props.navigationCollapsed ? '展开导航栏' : '收起导航栏'
})

async function handleLogout() {
  await auth.logout()
  menuStore.reset()
  tabsStore.reset()
  await router.replace({ name: 'Login' })
}
</script>

<template>
  <el-header class="layout-header" height="var(--cw-topbar-height)">
    <div class="header-left">
      <el-button
        v-if="auth.isApproved"
        class="icon-button navigation-toggle"
        text
        :icon="navigationToggleIcon"
        :aria-label="navigationToggleLabel"
        @click="emit('navigationToggle', $event)"
      />
      <span class="header-separator" aria-hidden="true" />
      <div class="location-copy">
        <span class="location-product">customer_work · Agent Console</span>
        <strong>智能体运营台</strong>
      </div>
    </div>

    <div v-if="auth.isApproved" class="header-center">
      <GlobalCommandSearch />
    </div>

    <div class="header-right">
      <TenantSwitcher v-if="auth.isApproved" />
      <el-button
        class="icon-button"
        text
        :icon="themeStore.isDark ? 'Sunny' : 'Moon'"
        :title="themeStore.isDark ? '切换到亮色模式' : '切换到暗色模式'"
        :aria-label="themeStore.isDark ? '切换到亮色模式' : '切换到暗色模式'"
        @click="themeStore.toggleDark()"
      />
      <NotificationBell v-if="auth.isApproved" />
      <el-dropdown trigger="click">
        <button type="button" class="user-menu-trigger" aria-label="打开用户菜单">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-name">{{ auth.nickname }}</span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="router.push({ name: 'ChangePassword' })">修改密码</el-dropdown-item>
            <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-header>
</template>

<style scoped>
.layout-header {
  flex: 0 0 var(--cw-topbar-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px 0 12px;
  background: color-mix(in srgb, var(--el-bg-color) 96%, transparent);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
}

.header-left {
  flex: 0 1 auto;
  min-width: 150px;
  gap: 10px;
}

.header-center {
  flex: 1;
  min-width: 164px;
  display: flex;
  justify-content: center;
  padding: 0 8px;
}

.header-right {
  flex: 0 0 auto;
  gap: 4px;
}

.header-right :deep(.tenant-switcher) {
  width: 200px;
  margin-right: 4px;
}

.icon-button {
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: 9px;
  color: var(--el-text-color-regular);
  font-size: 18px;
}

.icon-button:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.header-separator {
  width: 1px;
  height: 28px;
  background: var(--el-border-color-lighter);
}

.location-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: nowrap;
}

.location-product {
  color: var(--el-text-color-placeholder);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.location-copy strong {
  font-size: 14px;
  font-weight: 650;
}

.user-menu-trigger {
  height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 6px 0 4px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
}

.user-menu-trigger:hover {
  background: var(--el-fill-color-light);
}

.user-avatar {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: linear-gradient(145deg, var(--el-color-primary-light-7), var(--el-color-primary-light-9));
  color: var(--el-color-primary-dark-2);
  font-size: 12px;
  font-weight: 750;
}

.user-name {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

@media (max-width: 1280px) {
  .header-right :deep(.tenant-switcher) {
    width: 174px;
  }

  .location-product {
    display: none;
  }
}

@media (max-width: 1120px) {
  .user-name,
  .header-separator {
    display: none;
  }

  .header-left {
    min-width: auto;
  }
}

@media (max-width: 900px) {
  .location-copy {
    display: none;
  }

  .header-right :deep(.tenant-switcher) {
    width: 150px;
  }
}
</style>
