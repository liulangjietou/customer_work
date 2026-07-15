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
        <!-- 头像占位：无真实头像资源，用图标圆形占位（离线可用） -->
        <div class="avatar">
          <van-icon name="manager" size="36" />
        </div>
        <div class="nickname">{{ profile?.nickname || auth.nickname || '用户' }}</div>
      </div>

      <van-cell-group inset>
        <van-cell title="昵称" :value="profile?.nickname || '-'" />
        <van-cell title="用户名" :value="profile?.username || '-'" />
        <van-cell title="手机号" :value="profile?.phone || '-'" />
      </van-cell-group>

      <div class="logout-wrap">
        <van-button type="danger" block round :loading="loading" @click="onLogout">退出登录</van-button>
      </div>
    </div>

    <AppTabbar />
  </div>
</template>

<style scoped>
.profile-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
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
  padding: 32px 0 24px;
}

.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1989fa;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nickname {
  margin-top: 12px;
  font-size: 18px;
  font-weight: 600;
  color: #323233;
}

.logout-wrap {
  margin: 32px 16px 0;
}
</style>
