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
    <div class="hero">
      <div class="hero-brand">注册账号</div>
      <div class="hero-sub">创建您的专属客服账号</div>
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
        <van-field
          v-model="nickname"
          name="nickname"
          label="昵称"
          placeholder="请输入昵称"
          :rules="[{ required: true, message: '请输入昵称' }]"
        />
        <van-field
          v-model="phone"
          name="phone"
          label="手机号"
          placeholder="请输入手机号"
          :rules="[{ required: true, message: '请输入手机号' }]"
        />
      </van-cell-group>
      <div class="submit-wrap">
        <van-button round block type="primary" native-type="submit" :loading="submitting">注册</van-button>
      </div>
    </van-form>
    <div class="link-wrap">
      已有账号？
      <router-link to="/login">去登录</router-link>
    </div>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--cw-page-bg);
}

.hero {
  background: var(--cw-gradient-brand);
  color: #fff;
  padding: 48px 24px 40px;
  text-align: center;
  border-radius: 0 0 28px 28px;
}

.hero-brand {
  font-size: 24px;
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
  margin-top: -20px;
  padding-top: 24px;
  box-shadow: var(--cw-card-shadow);
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
