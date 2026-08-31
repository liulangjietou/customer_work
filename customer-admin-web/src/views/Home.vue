<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useTabsStore } from '@/store/tabs'
import HomeAdmission from './home/components/HomeAdmission.vue'
import HomeHero from './home/components/HomeHero.vue'
import HomeWorkBoard from './home/components/HomeWorkBoard.vue'
import {
  buildHomeAdmissionPresentation,
  buildHomeSnapshot,
  type HomeEntry,
} from './homePresentation'

const auth = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()
const router = useRouter()

const snapshot = computed(() => buildHomeSnapshot({
  approved: auth.isApproved,
  menuTree: menuStore.tree,
  tabs: tabsStore.tabs,
}))

const displayName = computed(() => auth.nickname || auth.username || '使用者')
const accountName = computed(() => auth.username || '当前登录账号')

const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  weekday: 'short',
}).format(new Date())

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const primaryEntry = computed<HomeEntry | undefined>(() => (
  snapshot.value.quickEntries.find((entry) => entry.dynamic)
  ?? snapshot.value.quickEntries[0]
))

const admissionPresentation = computed(() => buildHomeAdmissionPresentation(
  auth.approvalStatus,
  auth.approvalRemark,
))

function navigate(path: string) {
  router.push(path)
}
</script>

<template>
  <main v-if="auth.isApproved" class="home-page" aria-labelledby="home-title">
    <div class="home-canvas">
      <HomeHero
        :display-name="displayName"
        :today-label="todayLabel"
        :greeting="greeting"
        :routes-registered="menuStore.routesRegistered"
        :snapshot="snapshot"
        :primary-entry="primaryEntry"
        @navigate="navigate"
      />
      <HomeWorkBoard :snapshot="snapshot" @navigate="navigate" />
    </div>
  </main>

  <main v-else class="home-page home-page--admission" aria-labelledby="home-title">
    <HomeAdmission
      :display-name="displayName"
      :username="accountName"
      :presentation="admissionPresentation"
    />
  </main>
</template>

<style scoped>
.home-page {
  --home-ink: var(--cw-ink, #0b1630);
  --home-ink-soft: var(--cw-ink-elevated, #152441);
  --home-cobalt: var(--theme-primary, var(--cw-cobalt, #3e63dd));
  --home-amber: var(--cw-amber, #d99217);
  --home-signal: color-mix(in srgb, var(--home-amber) 72%, white);
  --home-success: var(--cw-success, #16856a);
  --home-on-ink: #f7fbff;
  --home-on-ink-muted: color-mix(in srgb, white 60%, var(--home-ink));
  --home-on-ink-soft: color-mix(in srgb, white 86%, var(--home-ink));
  --home-on-ink-strong: color-mix(in srgb, white 94%, var(--home-ink));
  --home-paper: var(--cw-paper, var(--el-bg-color, #ffffff));
  --home-canvas: var(--cw-canvas, var(--el-bg-color-page, #f4f6fa));
  --home-line: var(--cw-line, var(--el-border-color-lighter, #dce1ea));
  --home-text: var(--cw-text, var(--el-text-color-primary, #172033));
  --home-muted: var(--cw-text-muted, var(--el-text-color-secondary, #677189));
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: auto;
  color: var(--home-text);
  background:
    linear-gradient(color-mix(in srgb, var(--home-line) 25%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--home-line) 25%, transparent) 1px, transparent 1px),
    var(--home-canvas);
  background-size: 32px 32px;
  scrollbar-gutter: stable;
}

.home-canvas {
  width: min(100%, 1560px);
  min-height: 100%;
  margin: 0 auto;
  padding: clamp(18px, 2.4vw, 36px);
  box-sizing: border-box;
}

.home-page--admission {
  display: grid;
  place-items: center;
}

@media (max-width: 820px) {
  .home-canvas {
    padding: 16px;
  }
}

@media (max-width: 520px) {
  .home-page {
    scrollbar-gutter: auto;
  }

  .home-canvas {
    padding: 12px;
  }
}
</style>
