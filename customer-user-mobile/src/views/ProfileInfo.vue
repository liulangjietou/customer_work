<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import type { UploaderAfterRead, UploaderBeforeRead, UploaderFileListItem } from 'vant'
import { fetchMe, uploadAvatar } from '@/api/auth'
import type { UserInfo } from '@/types/api'

// 头像上传限制：与后端契约对齐（png/jpg/jpeg/gif，≤2MB），前端先校验一次减少无效上传
const ACCEPTED_AVATAR_TYPES = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif']
const MAX_AVATAR_BYTES = 2 * 1024 * 1024

const router = useRouter()

const profile = ref<UserInfo | null>(null)
const loading = ref(true)
const uploading = ref(false)

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await fetchMe()
  } finally {
    loading.value = false
  }
}

onMounted(loadProfile)

const beforeReadAvatar: UploaderBeforeRead = (file) => {
  const target = Array.isArray(file) ? file[0] : file
  if (!ACCEPTED_AVATAR_TYPES.includes(target.type)) {
    showToast('仅支持 png/jpg/jpeg/gif 格式')
    return false
  }
  if (target.size > MAX_AVATAR_BYTES) {
    showToast('图片大小不能超过 2MB')
    return false
  }
  return true
}

const afterReadAvatar: UploaderAfterRead = async (file) => {
  const item = Array.isArray(file) ? file[0] : (file as UploaderFileListItem)
  const rawFile = item.file
  if (!rawFile) {
    return
  }
  uploading.value = true
  try {
    const avatarUrl = await uploadAvatar(rawFile)
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
    <van-nav-bar title="个人信息" left-arrow @click-left="router.back()" />

    <div class="content">
      <van-loading v-if="loading" class="loading" vertical>加载中...</van-loading>
      <template v-else>
        <div class="avatar-section">
          <van-uploader :before-read="beforeReadAvatar" :after-read="afterReadAvatar" :max-count="1">
            <div class="avatar-tap">
              <img v-if="profile?.avatarUrl" :src="profile.avatarUrl" class="avatar-img" alt="头像" />
              <van-icon v-else name="manager" size="40" />
              <div class="avatar-badge">
                <van-icon name="photograph" size="14" />
              </div>
            </div>
          </van-uploader>
          <div class="avatar-tip">{{ uploading ? '上传中...' : '点击更换头像' }}</div>
        </div>

        <van-cell-group inset>
          <van-cell title="昵称" :value="profile?.nickname || '-'" />
          <van-cell title="用户名" :value="profile?.username || '-'" />
          <van-cell title="手机号" :value="profile?.phone || '-'" />
        </van-cell-group>
      </template>
    </div>
  </div>
</template>

<style scoped>
.profile-info-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.content {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 24px;
}

.loading {
  margin-top: 40vh;
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 0 24px;
}

.avatar-tap {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--cw-card-bg);
  color: var(--cw-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  box-shadow: var(--cw-card-shadow);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-badge {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--cw-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--cw-card-bg);
}

.avatar-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--cw-text-secondary);
}
</style>
