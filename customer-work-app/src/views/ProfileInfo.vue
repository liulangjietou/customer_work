<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { fetchMe, uploadAvatar } from '@/api/auth'
import UserAvatar from '@/components/UserAvatar.vue'
import type { UserInfo } from '@/types/api'

// 头像上传限制：与后端契约对齐（png/jpg/jpeg/gif，≤2MB），前端先校验一次减少无效上传
const ACCEPTED_AVATAR_TYPES = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif']
const MAX_AVATAR_BYTES = 2 * 1024 * 1024

const router = useRouter()

const profile = ref<UserInfo | null>(null)
const loading = ref(true)
const loadFailed = ref(false)
const uploading = ref(false)

const displayName = computed(() => profile.value?.nickname || profile.value?.username || '用户')

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

function validateAvatar(file: File): boolean {
  if (!ACCEPTED_AVATAR_TYPES.includes(file.type)) {
    showToast('仅支持 png/jpg/jpeg/gif 格式')
    return false
  }
  if (file.size > MAX_AVATAR_BYTES) {
    showToast('图片大小不能超过 2MB')
    return false
  }
  return true
}

async function onAvatarSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 允许用户在失败后再次选择同一个文件。
  input.value = ''
  if (!file || !validateAvatar(file)) {
    return
  }
  uploading.value = true
  try {
    const avatarUrl = await uploadAvatar(file)
    if (profile.value) {
      profile.value = { ...profile.value, avatarUrl }
    }
    showToast('头像已更新')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="profile-info-page">
    <header class="detail-header">
      <button type="button" class="back-button" aria-label="返回" @click="router.back()">
        <van-icon name="arrow-left" />
      </button>
      <div>
        <h1>个人信息</h1>
        <p>账户资料</p>
      </div>
      <span class="header-spacer" aria-hidden="true"></span>
    </header>

    <main class="content">
      <div v-if="loading" class="loading-state" aria-busy="true" aria-label="正在加载个人信息">
        <span class="skeleton avatar-skeleton"></span>
        <span class="skeleton tip-skeleton"></span>
        <div class="field-card skeleton-card">
          <div v-for="index in 3" :key="index" class="field-row">
            <span class="skeleton label-skeleton"></span>
            <span class="skeleton value-skeleton"></span>
          </div>
        </div>
      </div>

      <section v-else-if="loadFailed" class="error-state" role="alert">
        <span class="error-icon"><van-icon name="contact" /></span>
        <h2>资料加载失败</h2>
        <p>暂时无法获取个人资料，请检查网络后重新加载。</p>
        <button type="button" class="retry-button" @click="loadProfile">
          <van-icon name="replay" />
          重新加载
        </button>
      </section>

      <template v-else-if="profile">
        <section class="avatar-section">
          <label class="avatar-upload-control">
            <input
              class="avatar-upload-input"
              type="file"
              accept="image/png,image/jpeg,image/gif"
              :disabled="uploading"
              aria-label="更换头像"
              @change="onAvatarSelected"
            />
            <div class="avatar-tap" :aria-busy="uploading">
              <UserAvatar :src="profile.avatarUrl" :name="displayName" />
              <span class="avatar-badge">
                <van-loading v-if="uploading" size="13" color="#fff" />
                <van-icon v-else name="photograph" />
              </span>
            </div>
          </label>
          <p class="avatar-tip">
            {{ uploading ? '正在上传头像…' : '点击头像更换 · PNG/JPG/GIF，最大 2MB' }}
          </p>
        </section>

        <section class="readonly-section" aria-labelledby="readonlyTitle">
          <div class="section-heading">
            <h2 id="readonlyTitle">基本资料</h2>
            <span>只读</span>
          </div>
          <div class="field-card">
            <div class="field-row">
              <span class="field-label">昵称</span>
              <strong class="field-value">{{ profile.nickname || '-' }}</strong>
            </div>
            <div class="field-row">
              <span class="field-label">用户名</span>
              <strong class="field-value">{{ profile.username || '-' }}</strong>
            </div>
            <div class="field-row">
              <span class="field-label">手机号</span>
              <strong class="field-value">{{ profile.phone || '-' }}</strong>
            </div>
          </div>
          <p class="readonly-tip"><van-icon name="info-o" />账号字段由系统维护，当前不可直接修改</p>
        </section>
      </template>
    </main>
  </div>
</template>

<style scoped>
.profile-info-page {
  --info-ink: var(--cw-ink, #142033);
  --info-signal: var(--cw-primary, #316cff);
  --info-signal-soft: var(--cw-primary-soft, #edf2ff);
  --info-cloud: var(--cw-page-bg, #f2f5fa);
  --info-paper: var(--cw-card-bg, #fff);
  flex: 1;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--info-cloud);
  color: var(--info-ink);
}

.detail-header {
  min-height: 72px;
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 42px;
  align-items: center;
  padding: calc(10px + env(safe-area-inset-top)) 12px 10px;
  border-bottom: 1px solid rgba(223, 229, 238, 0.8);
  background: color-mix(in srgb, var(--info-paper) 94%, transparent);
  text-align: center;
}

.detail-header h1,
.detail-header p {
  margin: 0;
}

.detail-header h1 {
  font-size: 17px;
  font-weight: 750;
  letter-spacing: -0.015em;
}

.detail-header p {
  margin-top: 2px;
  color: #939eae;
  font-size: 9px;
}

.back-button {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 13px;
  background: transparent;
  color: var(--info-ink);
  font-size: 20px;
  cursor: pointer;
}

.back-button:active {
  background: #eef2f7;
}

.header-spacer {
  width: 40px;
  height: 40px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
  padding: 18px 14px calc(30px + env(safe-area-inset-bottom));
}

.content::-webkit-scrollbar {
  display: none;
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 18px 0 25px;
}

.avatar-upload-control {
  position: relative;
  display: inline-block;
  border-radius: 30px;
}

.avatar-upload-input {
  position: absolute;
  z-index: 4;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: inherit;
  opacity: 0;
  cursor: pointer;
}

.avatar-upload-input:disabled {
  cursor: wait;
}

.avatar-upload-input:focus-visible + .avatar-tap {
  outline: 3px solid rgba(49, 108, 255, 0.28);
  outline-offset: 4px;
}

.avatar-tap {
  position: relative;
  width: 86px;
  height: 86px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: visible;
  border: 3px solid #fff;
  border-radius: 29px;
  background: var(--info-signal);
  box-shadow: 0 14px 32px rgba(49, 108, 255, 0.22);
}

.avatar-tap :deep(.user-avatar) {
  --user-avatar-initials-size: 25px;
  --user-avatar-icon-size: 32px;
  border-radius: 25px;
}

.avatar-badge {
  position: absolute;
  z-index: 3;
  right: -7px;
  bottom: -7px;
  width: 29px;
  height: 29px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 3px solid var(--info-cloud);
  border-radius: 50%;
  background: var(--info-signal);
  color: #fff;
  font-size: 14px;
}

.avatar-tip {
  margin: 14px 0 0;
  color: #79869a;
  font-size: 11px;
}

.readonly-section {
  margin-top: 4px;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 3px 9px;
}

.section-heading h2 {
  margin: 0;
  color: #758297;
  font-size: 11px;
  font-weight: 680;
}

.section-heading span {
  padding: 3px 7px;
  border-radius: 999px;
  background: #e9edf4;
  color: #7b8799;
  font-size: 9px;
}

.field-card {
  overflow: hidden;
  border: 1px solid #e5eaf2;
  border-radius: 17px;
  background: var(--info-paper);
  box-shadow: 0 7px 22px rgba(37, 55, 85, 0.055);
}

.field-row {
  min-height: 64px;
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 12px 15px;
  border-bottom: 1px solid #eef1f5;
}

.field-row:last-child {
  border-bottom: 0;
}

.field-label {
  color: #788599;
  font-size: 12px;
}

.field-value {
  overflow: hidden;
  color: var(--info-ink);
  text-align: right;
  font-size: 13px;
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.readonly-tip {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 10px 4px 0;
  color: #919cad;
  font-size: 10px;
}

.error-state {
  min-height: calc(100% - 18px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 24px 46px;
  text-align: center;
}

.error-icon {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  background: var(--info-signal-soft);
  color: var(--info-signal);
  font-size: 29px;
  box-shadow: 0 10px 24px rgba(49, 108, 255, 0.12);
}

.error-state h2 {
  margin: 19px 0 0;
  font-size: 17px;
}

.error-state p {
  max-width: 270px;
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
  background: var(--info-signal);
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 12px 26px rgba(49, 108, 255, 0.2);
  cursor: pointer;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 18px 0 0;
}

.skeleton {
  display: block;
  background: linear-gradient(90deg, #e7ecf4 25%, #f6f8fb 50%, #e7ecf4 75%);
  background-size: 200% 100%;
  animation: skeleton-wave 1.25s ease-in-out infinite;
}

.avatar-skeleton {
  width: 86px;
  height: 86px;
  border-radius: 29px;
}

.tip-skeleton {
  width: 178px;
  height: 10px;
  margin: 15px 0 27px;
  border-radius: 999px;
}

.skeleton-card {
  width: 100%;
}

.label-skeleton,
.value-skeleton {
  height: 11px;
  border-radius: 999px;
}

.label-skeleton {
  width: 45px;
}

.value-skeleton {
  width: min(132px, 78%);
  justify-self: end;
}

.back-button:focus-visible,
.retry-button:focus-visible {
  outline: 3px solid rgba(49, 108, 255, 0.28);
  outline-offset: -3px;
}

@keyframes skeleton-wave {
  from {
    background-position: 160% 0;
  }
  to {
    background-position: -60% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton {
    animation: none;
  }
}
</style>
