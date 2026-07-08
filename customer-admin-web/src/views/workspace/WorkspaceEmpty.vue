<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()
</script>

<template>
  <div class="workspace-empty">
    <el-empty description="还没有已启用的智能体">
      <el-button v-if="auth.hasPermission('agent:add')" type="primary" @click="router.push('/aiconfig/agent')">
        去新建智能体
      </el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.workspace-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
