<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()

const homeMessage = computed(() => {
  if (auth.approvalStatus === 'PENDING') {
    return '账号正在等待管理员审核，审核并分配角色后即可查看菜单。'
  }
  if (auth.approvalStatus === 'REJECTED') {
    return auth.approvalRemark
      ? `账号审核未通过：${auth.approvalRemark}`
      : '账号审核未通过，请联系管理员。'
  }
  if (auth.permissions.length === 0) {
    return '账号尚未分配可用权限，请联系管理员。'
  }
  return '请从左侧菜单选择要管理的模块。'
})
</script>

<template>
  <div class="home-page">
    <img src="/home-cover.jpg" alt="首页" class="home-cover" />
    <p class="home-tip">
      <strong>{{ auth.nickname }}</strong>，{{ homeMessage }}
    </p>
  </div>
</template>

<style scoped>
.home-page {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.home-cover {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
}

.home-tip {
  position: absolute;
  bottom: clamp(20px, 4vh, 48px);
  left: 50%;
  z-index: 1;
  transform: translateX(-50%);
  isolation: isolate;
  margin: 0;
  padding: 10px 18px;
  width: max-content;
  max-width: calc(100% - 32px);
  border-radius: 999px;
  font-size: clamp(15px, 1.1vw, 20px);
  font-weight: 700;
  letter-spacing: 1px;
  overflow-wrap: anywhere;
  text-align: center;
  color: rgb(0 21 41 / 85%);
}

.home-tip strong {
  color: var(--el-color-primary);
}

.home-tip::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  border: 1px solid rgb(255 255 255 / 75%);
  border-radius: inherit;
  background: rgb(255 255 255 / 78%);
  box-shadow: 0 6px 20px rgb(0 21 41 / 12%);
  backdrop-filter: blur(10px);
}

@media (max-width: 768px) {
  .home-cover {
    object-position: 62% center;
  }

  .home-tip {
    bottom: 16px;
  }
}
</style>
