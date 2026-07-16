<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { changePassword } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import FooterCopyright from '@/components/FooterCopyright.vue'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

function validateConfirm(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '新密码长度需在 6~32 位之间', trigger: 'blur' },
  ],
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }],
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码修改成功，请重新登录')
    auth.clear()
    await router.replace({ name: 'Login' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="change-password-page">
    <el-card class="change-password-card">
      <template #header>
        <div class="title">{{ auth.forceChangePassword ? '首次登录请修改密码' : '修改密码' }}</div>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @keyup.enter="handleSubmit" @submit.prevent>
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">确认修改</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <FooterCopyright />
  </div>
</template>

<style scoped>
.change-password-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
}

.change-password-card {
  width: 420px;
  margin-bottom: auto;
  margin-top: auto;
}

.title {
  font-size: 16px;
  font-weight: 600;
}
</style>
