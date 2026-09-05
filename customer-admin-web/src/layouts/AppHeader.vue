<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import ThemePresetSelector from '@/components/ThemePresetSelector.vue'
import GlobalCommandSearch from './GlobalCommandSearch.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
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
const userInitial = computed(() =>
  (auth.nickname || auth.username || 'U').trim().slice(0, 1).toLocaleUpperCase(),
)

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
        aria-controls="lifecycle-navigation-shell"
        :aria-expanded="compactViewport ? overlayOpen : !navigationCollapsed"
        @click="emit('navigationToggle', $event)"
      />
      <span class="header-separator" aria-hidden="true" />
      <div class="location-copy">
        <AppBreadcrumb />
      </div>
    </div>

    <div v-if="auth.isApproved" class="header-center">
      <GlobalCommandSearch />
    </div>

    <div class="header-right">
      <TenantSwitcher v-if="auth.isApproved" />
      <ThemePresetSelector />
      <NotificationBell v-if="auth.isApproved" />
      <el-dropdown trigger="click">
        <button type="button" class="user-menu-trigger" aria-label="打开用户菜单">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-name">{{ auth.nickname }}</span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="router.push({ name: 'ChangePassword' })"
              >修改密码</el-dropdown-item
            >
            <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-header>
</template>

<style scoped>
.layout-header {
  position: relative;
  z-index: 1200;
  flex: 0 0 var(--cw-topbar-height);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px 0 10px;
  border-bottom: 1px solid var(--cw-line, var(--el-border-color-lighter));
  background: color-mix(in srgb, var(--cw-paper, var(--el-bg-color)) 97%, transparent);
  backdrop-filter: blur(12px);
  overflow: visible;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
}

.header-left {
  flex: 1 1 240px;
  min-width: 0;
  gap: 8px;
}

.header-center {
  flex: 1;
  min-width: 180px;
  display: flex;
  justify-content: center;
  padding: 0 6px;
}

.header-right {
  min-width: 0;
  flex: 0 0 auto;
  gap: 3px;
}

.header-right :deep(.tenant-switcher) {
  width: 188px;
  margin-right: 3px;
}

.icon-button {
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: var(--cw-radius-md, 8px);
  color: var(--cw-text-muted, var(--el-text-color-regular));
  font-size: 17px;
}

.icon-button:hover {
  background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 8%, transparent);
  color: var(--cw-cobalt, var(--el-color-primary));
}

.header-separator {
  width: 1px;
  height: 24px;
  background: var(--cw-line, var(--el-border-color-lighter));
}

.location-copy {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.user-menu-trigger {
  height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 6px 0 4px;
  border: 0;
  border-radius: var(--cw-radius-md, 8px);
  background: transparent;
  color: var(--cw-text-muted, var(--el-text-color-regular));
  cursor: pointer;
}

.user-menu-trigger:hover {
  background: color-mix(in srgb, var(--cw-cobalt, var(--el-color-primary)) 8%, transparent);
  color: var(--cw-cobalt, var(--el-color-primary));
}

.user-avatar {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--cw-radius-md, 8px);
  background: color-mix(
    in srgb,
    var(--cw-cobalt, var(--el-color-primary)) 11%,
    var(--cw-paper, var(--el-bg-color))
  );
  color: var(--cw-cobalt, var(--el-color-primary));
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
    width: 168px;
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
  .location-copy,
  .header-separator {
    display: none;
  }

  .header-right :deep(.tenant-switcher) {
    width: 148px;
  }
}

@media (max-width: 760px) {
  .layout-header {
    gap: 3px;
    padding: 0 7px;
  }

  .header-left {
    min-width: 36px;
    flex: 0 0 36px;
    gap: 0;
  }

  .header-center {
    min-width: 36px;
    flex: 0 0 36px;
    padding: 0;
  }

  .header-right {
    flex: 1 1 auto;
    justify-content: flex-end;
    gap: 1px;
  }

  .header-right :deep(.tenant-switcher) {
    width: 36px;
    margin-right: 0;
  }

  .icon-button,
  .user-menu-trigger {
    width: 34px;
    height: 34px;
  }

  .user-menu-trigger {
    justify-content: center;
    padding: 0;
  }

  .user-name,
  .user-menu-trigger > .el-icon {
    display: none;
  }
}

@media (max-width: 410px) {
  .layout-header {
    padding: 0 5px;
  }

  .header-left,
  .header-center {
    min-width: 34px;
    flex-basis: 34px;
  }
}
</style>
