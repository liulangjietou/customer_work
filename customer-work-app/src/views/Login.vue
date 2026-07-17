<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

// 仅本地便捷用途：记住的用户名/密码以 Base64 编码存本地，Base64 为混淆非加密，不具备安全性
const REMEMBERED_CREDENTIAL_KEY = 'cw-remembered-credential'

interface RememberedCredential {
  username: string
  password: string
}

function encodeCredential(credential: RememberedCredential): string {
  const json = JSON.stringify(credential)
  return window.btoa(unescape(encodeURIComponent(json)))
}

function decodeCredential(encoded: string): RememberedCredential | null {
  try {
    const json = decodeURIComponent(escape(window.atob(encoded)))
    const parsed = JSON.parse(json)
    if (parsed && typeof parsed.username === 'string' && typeof parsed.password === 'string') {
      return parsed
    }
    return null
  } catch {
    return null
  }
}

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const rememberMe = ref(false)
const submitting = ref(false)

onMounted(() => {
  const encoded = localStorage.getItem(REMEMBERED_CREDENTIAL_KEY)
  if (!encoded) {
    return
  }
  const credential = decodeCredential(encoded)
  if (!credential) {
    return
  }
  username.value = credential.username
  password.value = credential.password
  rememberMe.value = true
})

async function onSubmit() {
  submitting.value = true
  try {
    const result = await login({ username: username.value, password: password.value })
    authStore.applyLogin(result.token, result.userId, result.nickname)
    if (rememberMe.value) {
      localStorage.setItem(
        REMEMBERED_CREDENTIAL_KEY,
        encodeCredential({ username: username.value, password: password.value }),
      )
    } else {
      localStorage.removeItem(REMEMBERED_CREDENTIAL_KEY)
    }
    showToast('登录成功')
    const redirect = router.currentRoute.value.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/messages')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="hero">
      <div class="hero-brand">智能客服</div>
      <div class="hero-sub">随时随地，贴心服务</div>
    </div>
    <div class="card">
    <van-form @submit="onSubmit">
      <van-cell-group inset>
        <van-field
          v-model="username"
          name="username"
          label="用户名"
          placeholder="请输入用户名"
          :rules="[{ required: true, message: '请输入用户名' }]"
        />
        <van-field
          v-model="password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          :rules="[{ required: true, message: '请输入密码' }]"
        />
      </van-cell-group>
      <div class="remember-wrap">
        <van-checkbox v-model="rememberMe">记住用户名和密码</van-checkbox>
      </div>
      <div class="submit-wrap">
        <van-button round block type="primary" native-type="submit" :loading="submitting">登录</van-button>
      </div>
    </van-form>
    <div class="link-wrap">
      还没有账号？
      <router-link to="/register">立即注册</router-link>
    </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.hero {
  background: var(--cw-gradient-brand);
  color: #fff;
  padding: 64px 24px 56px;
  text-align: center;
  border-radius: 0 0 28px 28px;
}

.hero-brand {
  font-size: 26px;
  font-weight: 700;
}

.hero-sub {
  margin-top: 8px;
  font-size: 13px;
  opacity: 0.85;
}

.card {
  flex: 1;
  background: var(--cw-card-bg);
  border-radius: var(--cw-card-radius) var(--cw-card-radius) 0 0;
  margin-top: -28px;
  padding-top: 24px;
  box-shadow: var(--cw-card-shadow);
}

.remember-wrap {
  margin: 12px 16px 0;
}

.submit-wrap {
  margin: 24px 16px 0;
}

.link-wrap {
  text-align: center;
  margin-top: 16px;
  padding-bottom: 24px;
  font-size: 14px;
  color: var(--cw-text-secondary);
}

.link-wrap a {
  color: var(--cw-primary);
}
</style>
