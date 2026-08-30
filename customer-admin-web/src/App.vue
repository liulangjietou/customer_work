<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useThemeStore } from '@/store/theme'

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
