<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { fetchMe } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { chatSocket } from '@/utils/ws'
import type { UserInfo } from '@/types/api'
import AppTabbar from '@/components/AppTabbar.vue'

const router = useRouter()
const auth = useAuthStore()

const profile = ref<UserInfo | null>(null)
const loading = ref(true)

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await fetchMe()
  } finally {
    loading.value = false
  }
}

onMounted(loadProfile)

async function onLogout() {
  try {
    await showConfirmDialog({ title: '退出登录', message: '确认退出当前账号？' })
  } catch {
    return // 用户取消
  }
  auth.clear()
  chatSocket.close()
  showToast('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <div class="profile-page">
    <van-nav-bar title="我的" />

    <div class="content">
      <div class="header">
        <div class="avatar">
          <img v-if="profile?.avatarUrl" :src="profile.avatarUrl" class="avatar-img" alt="头像" />
          <van-icon v-else name="manager" size="36" />
        </div>
        <div class="nickname">{{ profile?.nickname || auth.nickname || '用户' }}</div>
      </div>

      <van-cell-group inset class="menu-group">
        <van-cell title="个人信息" icon="contact" is-link clickable @click="router.push('/profile/info')" />
      </van-cell-group>

      <van-cell-group inset class="menu-group">
        <van-cell title="退出登录" icon="warning-o" is-link clickable @click="onLogout" />
      </van-cell-group>
    </div>

    <AppTabbar />
  </div>
</template>

<style scoped>
.profile-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.content {
  flex: 1;
  overflow-y: auto;
  /* 底部预留 tabbar 高度，防遮挡 */
  padding-bottom: 66px;
}

.header {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 0 40px;
  background: var(--cw-gradient-brand);
  border-radius: 0 0 24px 24px;
  color: #fff;
}

.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #fff;
  color: var(--cw-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.nickname {
  margin-top: 12px;
  font-size: 18px;
  font-weight: 600;
  color: #fff;
}

.menu-group {
  margin-top: 16px;
}
</style>
