<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { register } from '@/api/auth'

const router = useRouter()

const username = ref('')
const password = ref('')
const nickname = ref('')
const phone = ref('')
const submitting = ref(false)

async function onSubmit() {
  submitting.value = true
  try {
    await register({ username: username.value, password: password.value, nickname: nickname.value, phone: phone.value })
    showToast('注册成功，请登录')
    router.replace('/login')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <section class="register-hero">
      <div class="register-brand">
        <span class="assistant-mark" aria-hidden="true"><span class="assistant-glyph"><i></i></span></span>
        <span>
          <strong>智能客服</strong>
          <small>SERVICE COMPANION</small>
        </span>
      </div>
      <h1>创建服务账号</h1>
      <p>完成注册后，即可保存会话、订单与服务进度。</p>
    </section>

    <section class="register-panel">
      <div class="panel-heading">
        <h2>填写账户资料</h2>
        <p>以下信息用于识别您的服务账号</p>
      </div>
      <van-form class="auth-form" @submit="onSubmit">
        <div class="input-group">
          <label for="registerUsername">用户名</label>
          <van-field
            id="registerUsername"
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
          <label for="registerPassword">密码</label>
          <van-field
            id="registerPassword"
            v-model="password"
            class="auth-field"
            type="password"
            name="password"
            left-icon="lock"
            placeholder="请输入密码"
            autocomplete="new-password"
            :rules="[{ required: true, message: '请输入密码' }]"
          />
        </div>
        <div class="input-group">
          <label for="registerNickname">昵称</label>
          <van-field
            id="registerNickname"
            v-model="nickname"
            class="auth-field"
            name="nickname"
            left-icon="smile-o"
            placeholder="请输入昵称"
            autocomplete="name"
            :rules="[{ required: true, message: '请输入昵称' }]"
          />
        </div>
        <div class="input-group">
          <label for="registerPhone">手机号</label>
          <van-field
            id="registerPhone"
            v-model="phone"
            class="auth-field"
            type="tel"
            name="phone"
            left-icon="phone-o"
            placeholder="请输入手机号"
            autocomplete="tel"
            :rules="[{ required: true, message: '请输入手机号' }]"
          />
        </div>
        <van-button
          class="submit-button"
          block
          type="primary"
          native-type="submit"
          :loading="submitting"
          loading-text="正在注册…"
        >
          注册
        </van-button>
      </van-form>
      <p class="link-wrap">
        已有账号？
        <router-link to="/login">去登录</router-link>
      </p>
    </section>
  </div>
</template>

<style scoped>
.register-page {
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

.register-page::-webkit-scrollbar {
  display: none;
}

.register-hero {
  position: relative;
  min-height: 253px;
  flex: 0 0 auto;
  overflow: hidden;
  padding: calc(46px + env(safe-area-inset-top)) 28px 49px;
  background: var(--auth-ink);
  color: #fff;
}

.register-hero::before,
.register-hero::after {
  content: '';
  position: absolute;
  border: 1px solid rgba(94, 140, 255, 0.38);
  border-radius: 50%;
  pointer-events: none;
}

.register-hero::before {
  width: 238px;
  height: 238px;
  right: -108px;
  top: -112px;
  box-shadow: 0 0 0 31px rgba(49, 108, 255, 0.05);
}

.register-hero::after {
  width: 104px;
  height: 104px;
  left: -59px;
  bottom: -52px;
}

.register-brand {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 29px;
}

.assistant-mark {
  position: relative;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
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
  inset: -11px;
  animation-delay: 0.8s;
}

.assistant-glyph {
  position: relative;
  width: 22px;
  height: 17px;
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
  left: 4px;
}

.assistant-glyph::after {
  right: 4px;
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

.register-brand strong,
.register-brand small {
  display: block;
}

.register-brand strong {
  font-size: 14px;
}

.register-brand small {
  margin-top: 3px;
  color: #8fa0bb;
  font-size: 8px;
  letter-spacing: 0.08em;
}

.register-hero h1,
.register-hero p {
  position: relative;
  z-index: 1;
}

.register-hero h1 {
  margin: 0;
  font-size: 29px;
  line-height: 1.22;
  letter-spacing: -0.04em;
}

.register-hero p {
  max-width: 285px;
  margin: 9px 0 0;
  color: #aebbd0;
  font-size: 12px;
  line-height: 1.65;
}

.register-panel {
  position: relative;
  z-index: 2;
  flex: 1;
  margin-top: -30px;
  padding: 25px 22px calc(28px + env(safe-area-inset-bottom));
  border-radius: 30px 30px 0 0;
  background: var(--auth-paper);
}

.panel-heading {
  margin: 0 1px 21px;
}

.panel-heading h2,
.panel-heading p {
  margin: 0;
}

.panel-heading h2 {
  font-size: 17px;
  letter-spacing: -0.02em;
}

.panel-heading p {
  margin-top: 5px;
  color: #8a95a6;
  font-size: 10px;
}

.input-group {
  margin-bottom: 13px;
}

.input-group > label {
  display: block;
  margin: 0 2px 6px;
  color: #637087;
  font-size: 11px;
  font-weight: 680;
}

.auth-field {
  min-height: 50px;
  padding: 7px 14px;
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

.submit-button {
  min-height: 50px;
  margin-top: 9px;
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
  .register-hero {
    padding-inline: 22px;
  }

  .register-panel {
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
