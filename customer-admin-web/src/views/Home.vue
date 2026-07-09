<script setup lang="ts">
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
</script>

<template>
  <el-empty description="欢迎使用智能体客服后台管理系统">
    <template #image>
      <el-icon :size="64"><Platform /></el-icon>
    </template>
    <p>{{ auth.nickname }}，请从左侧菜单选择要管理的模块。</p>
  </el-empty>
</template>
