<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { fetchMe, revokeSessions } from '@/api/auth'
import AppTabbar from '@/components/AppTabbar.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useAuthStore } from '@/store/auth'
import type { UserInfo } from '@/types/api'
import { chatSocket } from '@/utils/ws'

type LogoutAction = 'current' | 'all'
const LEGACY_CREDENTIAL_KEY = 'cw-remembered-credential'

const router = useRouter()
const auth = useAuthStore()

const profile = ref<UserInfo | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
const logoutAction = ref<LogoutAction | null>(null)
const revoking = ref(false)
const logoutSheet = ref<HTMLElement | null>(null)
const confirmLogoutButton = ref<HTMLButtonElement | null>(null)
let logoutTrigger: HTMLElement | null = null

const displayName = computed(() => profile.value?.nickname || auth.nickname || '用户')
const accountSummary = computed(() => {
  const username = profile.value?.username?.trim()
  return username ? `@${username} · 当前设备已登录` : '当前设备已登录'
})
const logoutTitle = computed(() => (logoutAction.value === 'all' ? '退出所有设备？' : '退出当前设备？'))
const logoutMessage = computed(() =>
  logoutAction.value === 'all'
    ? '该账号在手机、电脑等全部设备上的登录状态都会被撤销，所有设备都需要重新登录。'
    : '仅结束当前设备的登录状态，其他设备不会受到影响。',
)

async function loadProfile() {
  loading.value = true
  loadFailed.value = false
  profile.value = null
  try {
    profile.value = await fetchMe()
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(loadProfile)

function openLogoutSheet(action: LogoutAction) {
  logoutTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  logoutAction.value = action
  nextTick(() => confirmLogoutButton.value?.focus())
}

function closeLogoutSheet() {
  if (!revoking.value) {
    logoutAction.value = null
    nextTick(() => {
      logoutTrigger?.focus()
      logoutTrigger = null
    })
  }
}

function onLogoutSheetKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeLogoutSheet()
    return
  }
  if (event.key !== 'Tab' || !logoutSheet.value) {
    return
  }

  const focusable = Array.from(
    logoutSheet.value.querySelectorAll<HTMLElement>('button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'),
  )
  if (focusable.length === 0) {
    event.preventDefault()
    logoutSheet.value.focus()
    return
  }
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

function clearCurrentSession(message: string) {
  logoutAction.value = null
  // 清理旧版本遗留的 Base64 密码；新的“记住用户名”不保存密码，可安全保留。
  localStorage.removeItem(LEGACY_CREDENTIAL_KEY)
  auth.clear()
  chatSocket.close()
  showToast(message)
  router.replace('/login')
}

async function confirmLogout() {
  if (!logoutAction.value || revoking.value) {
    return
  }

  if (logoutAction.value === 'current') {
    clearCurrentSession('已退出当前设备')
    return
  }

  revoking.value = true
  try {
    await revokeSessions()
    clearCurrentSession('所有设备已退出登录')
  } catch {
    // 全部会话撤销失败时必须保留当前登录态，便于用户重试。
    return
  } finally {
    revoking.value = false
  }
}
</script>

<template>
  <div class="profile-page">
    <header class="account-header" :aria-hidden="logoutAction ? 'true' : undefined" :inert="!!logoutAction">
      <div>
        <h1>我的</h1>
        <p>账户中心</p>
      </div>
    </header>

    <main class="profile-scroll" :aria-hidden="logoutAction ? 'true' : undefined" :inert="!!logoutAction">
      <div v-if="loading" class="profile-loading" aria-busy="true" aria-label="正在加载个人资料">
        <div class="profile-hero skeleton-hero">
          <span class="skeleton skeleton-avatar"></span>
          <span class="skeleton-copy">
            <i class="skeleton skeleton-line skeleton-line-short"></i>
            <i class="skeleton skeleton-line skeleton-line-title"></i>
            <i class="skeleton skeleton-line"></i>
          </span>
        </div>
        <section v-for="section in 2" :key="section" class="menu-section">
          <span class="skeleton skeleton-label"></span>
          <div class="menu-card skeleton-card">
            <span class="skeleton skeleton-menu-icon"></span>
            <span class="skeleton-copy">
              <i class="skeleton skeleton-line skeleton-line-title"></i>
              <i class="skeleton skeleton-line"></i>
            </span>
          </div>
        </section>
      </div>

      <section v-else-if="loadFailed" class="state-panel" role="alert">
        <span class="state-icon"><van-icon name="contact" /></span>
        <h2>个人资料加载失败</h2>
        <p>当前先不显示缓存资料，避免把异常信息当成真实资料。</p>
        <button type="button" class="retry-button" @click="loadProfile">
          <van-icon name="replay" />
          重新加载
        </button>
      </section>

      <template v-else-if="profile">
        <section class="profile-hero">
          <div class="avatar-frame">
            <UserAvatar :src="profile.avatarUrl" :name="displayName" />
            <span class="online-dot" aria-label="当前在线"></span>
          </div>
          <div class="profile-name">
            <small>服务账号</small>
            <h2>{{ displayName }}</h2>
            <p>{{ accountSummary }}</p>
          </div>
        </section>

        <div class="profile-content">
          <section class="menu-section" aria-labelledby="accountInfoTitle">
            <h2 id="accountInfoTitle">账户信息</h2>
            <div class="menu-card">
              <button type="button" class="menu-row" @click="router.push('/profile/info')">
                <span class="menu-icon"><van-icon name="contact" /></span>
                <span class="menu-copy">
                  <strong>个人信息</strong>
                  <small>头像、昵称、用户名与手机号</small>
                </span>
                <van-icon class="menu-chevron" name="arrow" />
              </button>
            </div>
          </section>

          <section class="menu-section" aria-labelledby="securityTitle">
            <h2 id="securityTitle">登录与安全</h2>
            <div class="menu-card">
              <button type="button" class="menu-row" @click="openLogoutSheet('current')">
                <span class="menu-icon neutral"><van-icon name="sign" /></span>
                <span class="menu-copy">
                  <strong>退出当前设备</strong>
                  <small>仅结束这台设备的登录状态</small>
                </span>
                <van-icon class="menu-chevron" name="arrow" />
              </button>
              <button type="button" class="menu-row danger" @click="openLogoutSheet('all')">
                <span class="menu-icon danger"><van-icon name="close" /></span>
                <span class="menu-copy">
                  <strong>退出所有设备</strong>
                  <small>全部设备都需要重新登录</small>
                </span>
                <van-icon class="menu-chevron" name="arrow" />
              </button>
            </div>
          </section>
        </div>
      </template>
    </main>

    <AppTabbar :aria-hidden="logoutAction ? 'true' : undefined" :inert="!!logoutAction" />

    <Transition name="sheet">
      <div v-if="logoutAction" class="logout-overlay" @click.self="closeLogoutSheet">
        <section
          ref="logoutSheet"
          class="logout-sheet"
          role="dialog"
          tabindex="-1"
          aria-modal="true"
          aria-labelledby="logoutSheetTitle"
          aria-describedby="logoutSheetMessage"
          @keydown="onLogoutSheetKeydown"
        >
          <span class="sheet-handle" aria-hidden="true"></span>
          <span class="sheet-icon" :class="{ danger: logoutAction === 'all' }">
            <van-icon :name="logoutAction === 'all' ? 'close' : 'sign'" />
          </span>
          <h2 id="logoutSheetTitle">{{ logoutTitle }}</h2>
          <p id="logoutSheetMessage">{{ logoutMessage }}</p>
          <div class="sheet-actions">
            <button type="button" class="sheet-button secondary" :disabled="revoking" @click="closeLogoutSheet">
              取消
            </button>
            <button
              ref="confirmLogoutButton"
              type="button"
              class="sheet-button danger"
              :disabled="revoking"
              @click="confirmLogout"
            >
              <van-loading v-if="revoking" size="17" color="#fff" />
              <span v-else>确认退出</span>
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.profile-page {
  --account-ink: var(--cw-ink, #142033);
  --account-signal: var(--cw-primary, #316cff);
  --account-signal-soft: var(--cw-primary-soft, #edf2ff);
  --account-lake: var(--cw-success, #19a995);
  --account-cloud: var(--cw-page-bg, #f2f5fa);
  --account-paper: var(--cw-card-bg, #fff);
  --account-ember: var(--cw-danger, #df5458);
  position: relative;
  flex: 1;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--account-cloud);
  color: var(--account-ink);
}

.account-header {
  flex: 0 0 auto;
  min-height: 72px;
  display: flex;
  align-items: center;
  padding: calc(13px + env(safe-area-inset-top)) 18px 12px;
  background: color-mix(in srgb, var(--account-paper) 94%, transparent);
  border-bottom: 1px solid rgba(223, 229, 238, 0.8);
}

.account-header h1,
.account-header p {
  margin: 0;
}

.account-header h1 {
  font-size: 19px;
  font-weight: 760;
  letter-spacing: -0.02em;
}

.account-header p {
  margin-top: 3px;
  color: #8995a7;
  font-size: 11px;
}

.profile-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
  padding: 16px 16px var(--cw-tabbar-space, 104px);
}

.profile-scroll::-webkit-scrollbar {
  display: none;
}

.profile-hero {
  position: relative;
  overflow: hidden;
  min-height: 108px;
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  align-items: center;
  gap: 15px;
  padding: 19px;
  border-radius: 23px;
  background: var(--account-ink);
  color: #fff;
  box-shadow: 0 18px 38px rgba(20, 32, 51, 0.2);
}

.profile-hero::before {
  content: '';
  position: absolute;
  right: -48px;
  top: -75px;
  width: 160px;
  height: 160px;
  border: 1px solid rgba(105, 150, 255, 0.34);
  border-radius: 50%;
  box-shadow: 0 0 0 22px rgba(49, 108, 255, 0.055);
  pointer-events: none;
}

.avatar-frame {
  position: relative;
  width: 66px;
  height: 66px;
  overflow: visible;
  border: 3px solid rgba(255, 255, 255, 0.78);
  border-radius: 22px;
  background: var(--account-signal);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.2);
}

.avatar-frame :deep(.user-avatar) {
  --user-avatar-initials-size: 20px;
  --user-avatar-icon-size: 27px;
  border-radius: 19px;
}

.online-dot {
  position: absolute;
  right: -5px;
  bottom: -5px;
  width: 17px;
  height: 17px;
  border: 3px solid var(--account-ink);
  border-radius: 50%;
  background: var(--account-lake);
}

.profile-name {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.profile-name small {
  display: block;
  margin-bottom: 4px;
  color: #8ea0bd;
  font-size: 10px;
  letter-spacing: 0.08em;
}

.profile-name h2,
.profile-name p {
  overflow: hidden;
  margin: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-name h2 {
  font-size: 20px;
  letter-spacing: -0.025em;
}

.profile-name p {
  margin-top: 7px;
  color: #aebbd0;
  font-size: 10px;
}

.profile-content {
  padding-top: 2px;
}

.menu-section {
  margin-top: 18px;
}

.menu-section > h2 {
  margin: 0 3px 9px;
  color: #758297;
  font-size: 11px;
  font-weight: 680;
}

.menu-card {
  overflow: hidden;
  border: 1px solid rgba(226, 232, 241, 0.9);
  border-radius: 17px;
  background: var(--account-paper);
  box-shadow: 0 7px 22px rgba(37, 55, 85, 0.055);
}

.menu-row {
  width: 100%;
  min-height: 66px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 0;
  border-bottom: 1px solid #eef1f5;
  background: var(--account-paper);
  color: inherit;
  text-align: left;
  font: inherit;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.menu-row:last-child {
  border-bottom: 0;
}

.menu-row:active {
  background: #f8faff;
}

.menu-icon {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--account-signal-soft);
  color: var(--account-signal);
  font-size: 18px;
}

.menu-icon.neutral {
  background: #eff2f6;
  color: #68768b;
}

.menu-icon.danger {
  background: #fff0f0;
  color: var(--account-ember);
}

.menu-copy {
  min-width: 0;
}

.menu-copy strong,
.menu-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-copy strong {
  font-size: 13px;
  font-weight: 690;
}

.menu-copy small {
  margin-top: 4px;
  color: #8c98aa;
  font-size: 10px;
}

.menu-row.danger .menu-copy strong {
  color: #c94b4b;
}

.menu-chevron {
  justify-self: end;
  color: #a1aab8;
  font-size: 17px;
}

.state-panel {
  min-height: calc(100% - 20px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 34px 24px 56px;
  text-align: center;
}

.state-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  background: var(--account-signal-soft);
  color: var(--account-signal);
  font-size: 29px;
  box-shadow: 0 10px 24px rgba(49, 108, 255, 0.12);
}

.state-panel h2 {
  margin: 19px 0 0;
  font-size: 17px;
}

.state-panel p {
  max-width: 286px;
  margin: 9px 0 0;
  color: #7c899d;
  font-size: 12px;
  line-height: 1.7;
}

.retry-button {
  min-width: 132px;
  min-height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 22px;
  padding: 0 20px;
  border: 0;
  border-radius: 15px;
  background: var(--account-signal);
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 12px 26px rgba(49, 108, 255, 0.2);
  cursor: pointer;
}

.profile-loading {
  padding-bottom: 12px;
}

.skeleton {
  display: block;
  overflow: hidden;
  background: linear-gradient(90deg, #e8edf5 25%, #f5f7fb 50%, #e8edf5 75%);
  background-size: 200% 100%;
  animation: skeleton-wave 1.25s ease-in-out infinite;
}

.skeleton-hero::before {
  display: none;
}

.skeleton-avatar {
  width: 66px;
  height: 66px;
  border-radius: 22px;
  opacity: 0.38;
}

.skeleton-copy {
  min-width: 0;
  display: grid;
  gap: 8px;
}

.skeleton-line {
  width: 88%;
  height: 9px;
  border-radius: 999px;
}

.skeleton-line-short {
  width: 38%;
  height: 7px;
  opacity: 0.56;
}

.skeleton-line-title {
  width: 62%;
  height: 13px;
}

.skeleton-label {
  width: 64px;
  height: 10px;
  margin: 0 3px 9px;
  border-radius: 999px;
}

.skeleton-card {
  min-height: 66px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
}

.skeleton-menu-icon {
  width: 36px;
  height: 36px;
  border-radius: 12px;
}

.logout-overlay {
  position: absolute;
  z-index: 100;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: 12px;
  background: rgba(15, 25, 40, 0.45);
  backdrop-filter: blur(3px);
}

.logout-sheet {
  width: 100%;
  padding: 9px 17px calc(17px + env(safe-area-inset-bottom));
  border-radius: 24px;
  background: var(--account-paper);
  box-shadow: 0 22px 60px rgba(14, 25, 43, 0.25);
  text-align: center;
}

.sheet-handle {
  width: 38px;
  height: 4px;
  display: block;
  margin: 0 auto 15px;
  border-radius: 999px;
  background: #d8dee8;
}

.sheet-icon {
  width: 46px;
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  background: #eff2f6;
  color: #66758a;
  font-size: 21px;
}

.sheet-icon.danger {
  background: #fff0f0;
  color: var(--account-ember);
}

.logout-sheet h2 {
  margin: 13px 0 0;
  font-size: 17px;
}

.logout-sheet p {
  margin: 8px auto 0;
  max-width: 320px;
  color: #78869a;
  font-size: 12px;
  line-height: 1.65;
}

.sheet-actions {
  display: grid;
  grid-template-columns: 1fr 1.28fr;
  gap: 10px;
  margin-top: 20px;
}

.sheet-button {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 15px;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.sheet-button.secondary {
  background: #eef2f7;
  color: #59687c;
}

.sheet-button.danger {
  background: var(--account-ember);
  color: #fff;
  box-shadow: 0 10px 24px rgba(223, 84, 88, 0.2);
}

.sheet-button:disabled {
  cursor: not-allowed;
  opacity: 0.68;
}

.menu-row:focus-visible,
.retry-button:focus-visible,
.sheet-button:focus-visible {
  outline: 3px solid rgba(49, 108, 255, 0.28);
  outline-offset: -3px;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 160ms ease;
}

.sheet-enter-active .logout-sheet,
.sheet-leave-active .logout-sheet {
  transition: transform 180ms ease;
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .logout-sheet,
.sheet-leave-to .logout-sheet {
  transform: translateY(24px);
}

@keyframes skeleton-wave {
  from {
    background-position: 160% 0;
  }
  to {
    background-position: -60% 0;
  }
}

@media (max-width: 340px) {
  .profile-scroll {
    padding-inline: 12px;
  }

  .profile-hero {
    grid-template-columns: 62px minmax(0, 1fr);
    gap: 12px;
    padding-inline: 16px;
  }

  .avatar-frame,
  .skeleton-avatar {
    width: 60px;
    height: 60px;
  }

}

@media (prefers-reduced-motion: reduce) {
  .skeleton,
  .sheet-enter-active,
  .sheet-leave-active,
  .sheet-enter-active .logout-sheet,
  .sheet-leave-active .logout-sheet {
    animation: none;
    transition: none;
  }
}
</style>
