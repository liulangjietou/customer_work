<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)

async function onSubmit() {
  submitting.value = true
  try {
    const result = await login({ username: username.value, password: password.value })
    authStore.applyLogin(result.token, result.userId, result.nickname)
    showToast('登录成功')
    const redirect = router.currentRoute.value.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/chat')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="brand">智能客服</div>
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
      <div class="submit-wrap">
        <van-button round block type="primary" native-type="submit" :loading="submitting">登录</van-button>
      </div>
    </van-form>
    <div class="link-wrap">
      还没有账号？
      <router-link to="/register">立即注册</router-link>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  padding-top: 15vh;
  flex: 1;
}

.brand {
  text-align: center;
  font-size: 22px;
  font-weight: 600;
  margin-bottom: 32px;
  color: #323233;
}

.submit-wrap {
  margin: 24px 16px 0;
}

.link-wrap {
  text-align: center;
  margin-top: 16px;
  font-size: 14px;
  color: #969799;
}

.link-wrap a {
  color: #1989fa;
}
</style>
