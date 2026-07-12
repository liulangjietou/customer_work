<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { login, ssoLogin } from '@/api/auth'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import FooterCopyright from '@/components/FooterCopyright.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()

/** local：账号密码登录（数据库） / sso：OA 域账号登录（LDAP/AD） */
const loginMode = ref<'local' | 'sso'>('local')

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '', rememberMe: false })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// “记住我”只记住用户名 + 是否勾选（不在前端存明文密码）；密码本身交给浏览器自带的密码管理器
// 记住（表单已加 autocomplete 属性），后端登录态有效期见 applyRememberedUsername 配套的 rememberMe 参数。
const REMEMBER_KEY_PREFIX = 'admin-remember-username-'

function loadRememberedUsername(mode: 'local' | 'sso') {
  const saved = localStorage.getItem(REMEMBER_KEY_PREFIX + mode)
  if (saved) {
    form.username = saved
    form.rememberMe = true
  } else {
    form.username = ''
    form.rememberMe = false
  }
}

function switchMode(mode: 'local' | 'sso') {
  loginMode.value = mode
  form.password = ''
  loadRememberedUsername(mode)
  formRef.value?.clearValidate()
}

loadRememberedUsername(loginMode.value)

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    const result = loginMode.value === 'local' ? await login(form) : await ssoLogin(form)
    const rememberKey = REMEMBER_KEY_PREFIX + loginMode.value
    if (form.rememberMe) {
      localStorage.setItem(rememberKey, form.username)
    } else {
      localStorage.removeItem(rememberKey)
    }
    auth.applyLoginResult(result, form.username)
    if (result.forceChangePassword) {
      ElMessage.warning('首次登录请先修改密码')
      await router.replace({ name: 'ChangePassword' })
      return
    }
    menuStore.reset()
    const redirect = (route.query.redirect as string) || '/'
    await router.replace(redirect)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="login-title">智能体客服后台管理系统</div>
      </template>
      <div class="login-tabs">
        <div
          class="login-tab"
          :class="{ active: loginMode === 'local' }"
          @click="switchMode('local')"
        >
          账号密码登录
        </div>
        <div
          class="login-tab"
          :class="{ active: loginMode === 'sso' }"
          @click="switchMode('sso')"
        >
          OA 登录
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="handleSubmit" @submit.prevent>
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            :placeholder="loginMode === 'local' ? '用户名' : 'OA 账号（如 RichardFyoung）'"
            size="large"
            autocomplete="username"
            :prefix-icon="'User'"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="loginMode === 'local' ? '密码' : 'OA 密码'"
            size="large"
            show-password
            autocomplete="current-password"
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.rememberMe">记住我（7 天内免登录）</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%" :loading="submitting" @click="handleSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <FooterCopyright dark />
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2937, #4b5563);
}

.login-card {
  width: 360px;
  margin-bottom: auto;
  margin-top: auto;
}

.login-title {
  text-align: center;
  font-size: 18px;
  font-weight: 600;
}

.login-tabs {
  display: flex;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color);
}

.login-tab {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.login-tab.active {
  color: var(--el-color-primary);
  border-bottom-color: var(--el-color-primary);
  font-weight: 600;
}
</style>
