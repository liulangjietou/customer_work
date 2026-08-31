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
  if (submitting.value) return
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) {
      return
    }
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
        <div class="card-heading">
          <span>ACCOUNT SECURITY</span>
          <h1>{{ auth.forceChangePassword ? '首次登录请修改密码' : '修改密码' }}</h1>
          <p>{{ auth.forceChangePassword ? '完成凭据更新后，才会开放当前账号的工作区访问。' : '更新当前账号凭据，提交成功后需要重新登录。' }}</p>
        </div>
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
          <el-button class="cw-final-action" type="primary" :loading="submitting" @click="handleSubmit">确认修改</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <FooterCopyright />
  </div>
</template>

<style scoped>
.change-password-page {
  box-sizing: border-box;
  height: 100%;
  min-height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 20px 18px;
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--cw-line) 28%, transparent) 1px, transparent 1px) 0 0 / 32px 32px,
    linear-gradient(color-mix(in srgb, var(--cw-line) 28%, transparent) 1px, transparent 1px) 0 0 / 32px 32px,
    var(--cw-canvas);
}

.change-password-card {
  width: min(440px, 100%);
  margin-bottom: auto;
  margin-top: auto;
  border-top: 3px solid var(--cw-amber);
}

.card-heading span {
  color: var(--cw-cobalt);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}

.card-heading h1 {
  margin: 6px 0 5px;
  color: var(--cw-text);
  font-size: 22px;
  letter-spacing: -.02em;
}

.card-heading p {
  margin: 0;
  color: var(--cw-text-muted);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.55;
}

@media (max-width: 480px) {
  .change-password-page {
    justify-content: flex-start;
    padding: 18px 12px 12px;
  }

  .change-password-card {
    margin-top: 12px;
  }

  .change-password-card :deep(.el-form-item) {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .change-password-card :deep(.el-form-item__label) {
    width: auto !important;
    justify-content: flex-start;
  }

  .change-password-card :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }

  .change-password-card .cw-final-action {
    width: 100%;
  }
}
</style>
