<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

const REMEMBERED_USERNAME_KEY = 'cw-remembered-username'
/** 旧版本曾把密码做 Base64 后写入 localStorage；Base64 不具备保密性，仅用于一次性迁移用户名后立即删除。 */
const LEGACY_CREDENTIAL_KEY = 'cw-remembered-credential'

function decodeLegacyUsername(encoded: string): string | null {
  try {
    const bytes = Uint8Array.from(window.atob(encoded), (character) => character.charCodeAt(0))
    const json = new TextDecoder().decode(bytes)
    const parsed = JSON.parse(json)
    if (parsed && typeof parsed.username === 'string') {
      return parsed.username
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
const rememberUsername = ref(false)
const submitting = ref(false)

onMounted(() => {
  const legacyCredential = localStorage.getItem(LEGACY_CREDENTIAL_KEY)
  // 无论是否已有新格式用户名，都先删除旧值，避免升级后旧密码因提前 return 继续驻留。
  localStorage.removeItem(LEGACY_CREDENTIAL_KEY)
  const rememberedUsername = localStorage.getItem(REMEMBERED_USERNAME_KEY)
  if (rememberedUsername) {
    username.value = rememberedUsername
    rememberUsername.value = true
    return
  }

  if (!legacyCredential) {
    return
  }
  const migratedUsername = decodeLegacyUsername(legacyCredential)
  if (migratedUsername) {
    username.value = migratedUsername
    rememberUsername.value = true
    localStorage.setItem(REMEMBERED_USERNAME_KEY, migratedUsername)
  }
})

async function onSubmit() {
  submitting.value = true
  try {
    const result = await login({ username: username.value, password: password.value })
    authStore.applyLogin(result.token, result.userId, result.nickname)
    localStorage.removeItem(LEGACY_CREDENTIAL_KEY)
    if (rememberUsername.value) {
      localStorage.setItem(REMEMBERED_USERNAME_KEY, username.value)
    } else {
      localStorage.removeItem(REMEMBERED_USERNAME_KEY)
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
    <section class="login-hero">
      <div class="login-brand">
        <span class="assistant-mark" aria-hidden="true"><span class="assistant-glyph"><i></i></span></span>
        <span>
          <strong>智能客服</strong>
          <small>SERVICE COMPANION</small>
        </span>
      </div>
      <h1>问题有人回应，<br />进度清晰可见。</h1>
      <p>登录后继续您的会话、订单与服务记录。</p>
    </section>

    <section class="login-panel">
      <van-form class="auth-form" @submit="onSubmit">
        <div class="input-group">
          <label for="loginUsername">用户名</label>
          <van-field
            id="loginUsername"
            v-model="username"
            class="auth-field"
            name="username"
            left-icon="contact"
            placeholder="请输入用户名"
            autocomplete="username"
            autocapitalize="none"
            :rules="[{ required: true, message: '请输入用户名' }]"
          />
        </div>
        <div class="input-group">
          <label for="loginPassword">密码</label>
          <van-field
            id="loginPassword"
            v-model="password"
            class="auth-field"
            type="password"
            name="password"
            left-icon="lock"
            placeholder="请输入密码"
            autocomplete="current-password"
            :rules="[{ required: true, message: '请输入密码' }]"
          />
        </div>
        <div class="remember-wrap">
          <van-checkbox v-model="rememberUsername" shape="square" icon-size="18px">记住用户名</van-checkbox>
        </div>
        <van-button
          class="submit-button"
          block
          type="primary"
          native-type="submit"
          :loading="submitting"
          loading-text="正在登录…"
        >
          登录
        </van-button>
      </van-form>
      <p class="link-wrap">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </p>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  --auth-ink: var(--cw-ink, #142033);
  --auth-signal: var(--cw-primary, #316cff);
  --auth-paper: var(--cw-card-bg, #fff);
  flex: 1;
  min-height: 100vh;
  min-height: 100dvh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  background: var(--auth-paper);
  color: var(--auth-ink);
  scrollbar-width: none;
}

.login-page::-webkit-scrollbar {
  display: none;
}

.login-hero {
  position: relative;
  min-height: 305px;
  flex: 0 0 auto;
  overflow: hidden;
  padding: calc(62px + env(safe-area-inset-top)) 28px 54px;
  background: var(--auth-ink);
  color: #fff;
}

.login-hero::before,
.login-hero::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(94, 140, 255, 0.38);
  border-radius: 50%;
  pointer-events: none;
}

.login-hero::before {
  width: 260px;
  height: 260px;
  right: -120px;
  top: -105px;
  box-shadow: 0 0 0 34px rgba(49, 108, 255, 0.05);
}

.login-hero::after {
  width: 112px;
  height: 112px;
  left: -66px;
  bottom: -46px;
}

.login-brand {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 36px;
}

.assistant-mark {
  position: relative;
  width: 46px;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 15px;
  background: #fff;
  color: var(--auth-signal);
  box-shadow: 0 10px 26px rgba(13, 38, 103, 0.22);
}

.assistant-mark::before,
.assistant-mark::after {
  content: '';
  position: absolute;
  border: 1px solid currentColor;
  border-radius: 50%;
  animation: beacon 2.4s ease-out infinite;
}

.assistant-mark::before {
  inset: -6px;
}

.assistant-mark::after {
  inset: -12px;
  animation-delay: 0.8s;
}

.assistant-glyph {
  position: relative;
  width: 23px;
  height: 18px;
  border: 2px solid currentColor;
  border-radius: 10px 10px 9px 9px;
}

.assistant-glyph::before,
.assistant-glyph::after {
  content: '';
  position: absolute;
  top: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
}

.assistant-glyph::before {
  left: 5px;
}

.assistant-glyph::after {
  right: 5px;
}

.assistant-glyph i {
  position: absolute;
  left: 50%;
  bottom: -6px;
  width: 8px;
  height: 7px;
  border-left: 2px solid currentColor;
  transform: skew(-24deg) translateX(-50%);
}

.login-brand strong,
.login-brand small {
  display: block;
}

.login-brand strong {
  font-size: 15px;
}

.login-brand small {
  margin-top: 3px;
  color: #8fa0bb;
  font-size: 9px;
  letter-spacing: 0.08em;
}

.login-hero h1,
.login-hero p {
  position: relative;
  z-index: 1;
}

.login-hero h1 {
  margin: 0;
  font-size: 31px;
  line-height: 1.22;
  letter-spacing: -0.045em;
}

.login-hero p {
  margin: 10px 0 0;
  color: #aebbd0;
  font-size: 12px;
  line-height: 1.65;
}

.login-panel {
  position: relative;
  z-index: 2;
  flex: 1;
  margin-top: -31px;
  padding: 27px 22px calc(28px + env(safe-area-inset-bottom));
  border-radius: 30px 30px 0 0;
  background: var(--auth-paper);
}

.input-group {
  margin-bottom: 16px;
}

.input-group > label {
  display: block;
  margin: 0 2px 7px;
  color: #637087;
  font-size: 11px;
  font-weight: 680;
}

.auth-field {
  min-height: 52px;
  padding: 8px 14px;
  border: 1px solid #dfe5ee;
  border-radius: 15px;
  background: #f8fafd;
  transition: border-color 150ms ease, box-shadow 150ms ease, background 150ms ease;
}

.auth-field::after {
  display: none;
}

.auth-field:focus-within {
  border-color: color-mix(in srgb, var(--auth-signal) 74%, #fff);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(49, 108, 255, 0.1);
}

.auth-field :deep(.van-field__left-icon) {
  margin-right: 10px;
  color: #8490a3;
  font-size: 18px;
}

.auth-field :deep(.van-field__control) {
  color: var(--auth-ink);
  font-size: 13px;
}

.auth-field :deep(.van-field__control::placeholder) {
  color: #a4adba;
}

.auth-field :deep(.van-field__error-message) {
  margin-top: 5px;
  font-size: 10px;
}

.remember-wrap {
  margin: 2px 2px 21px;
  color: #69768b;
  font-size: 11px;
}

.remember-wrap :deep(.van-checkbox__icon--checked .van-icon) {
  border-color: var(--auth-signal);
  background: var(--auth-signal);
}

.remember-wrap :deep(.van-checkbox__label) {
  color: inherit;
}

.submit-button {
  min-height: 50px;
  border: 0;
  border-radius: 16px;
  background: var(--auth-signal);
  font-size: 14px;
  font-weight: 750;
  box-shadow: 0 12px 26px rgba(49, 108, 255, 0.23);
}

.submit-button:focus-visible {
  outline: 3px solid rgba(49, 108, 255, 0.3);
  outline-offset: 3px;
}

.link-wrap {
  margin: 18px 0 0;
  text-align: center;
  color: #8792a3;
  font-size: 11px;
}

.link-wrap a {
  color: var(--auth-signal);
  font-weight: 700;
  text-decoration: none;
}

.link-wrap a:focus-visible {
  outline: 2px solid rgba(49, 108, 255, 0.32);
  outline-offset: 3px;
}

@keyframes beacon {
  0% {
    opacity: 0.34;
    transform: scale(0.88);
  }
  72%,
  100% {
    opacity: 0;
    transform: scale(1.08);
  }
}

@media (max-width: 340px) {
  .login-hero {
    padding-inline: 22px;
  }

  .login-panel {
    padding-inline: 17px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .assistant-mark::before,
  .assistant-mark::after {
    animation: none;
    opacity: 0.18;
  }
}
</style>
