<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useChatConversationsStore } from '@/store/chatConversations'
import { useVibeConversationsStore } from '@/store/vibeConversations'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useThemeStore } from '@/store/theme'

const auth = useAuthStore()
// 应用根层统一连接认证和工作区生命周期；同令牌重新登录也必须撤销旧状态。
watch(() => auth.loginGeneration, () => {
  useChatConversationsStore().resetForLogin()
  useVibeConversationsStore().resetForLogin()
}, { flush: 'sync' })
const themeStore = useThemeStore()
// 在首个路由组件渲染前应用已保存的明暗模式，避免全局壳先以亮色闪现再切暗色。
themeStore.apply()
onBeforeUnmount(() => themeStore.disposeSystemThemeListener())
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
