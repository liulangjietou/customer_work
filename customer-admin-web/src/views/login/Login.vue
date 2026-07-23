<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { login, ssoLogin } from '@/api/auth'
import { fetchLoginCarouselUrls } from '@/api/login-image'
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

// 登录页轮播背景图：先用 public/ 下的内置默认图兜底，挂载后实时拉取后台
// "系统管理 › 登录页图片"上传的启用图（免鉴权接口，见后端 LoginImagePublicController）；
// 拉取失败或列表为空时保持默认图，登录页永远有背景可展示。
const DEFAULT_BG_IMAGES = ['/A1.jpg', '/A2.jpg', '/A3.jpg']
const bgImages = ref<string[]>(DEFAULT_BG_IMAGES)

onMounted(async () => {
  try {
    const urls = await fetchLoginCarouselUrls()
    if (urls.length > 0) {
      bgImages.value = urls
    }
  } catch {
    // 后端不可用时静默保持默认图，不打扰登录
  }
})

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
    <div class="bg-carousel">
      <el-carousel type="fade" height="100%" :interval="1000" arrow="never" indicator-position="none">
        <el-carousel-item v-for="img in bgImages" :key="img">
          <div class="bg-slide" :style="{ backgroundImage: `url(${img})` }" />
        </el-carousel-item>
      </el-carousel>
      <div class="bg-overlay" />
    </div>
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
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

/* 背景轮播层：铺满整页，垫在登录卡片和页脚之下。深灰渐变作为兜底——背景图加载失败也不会白屏。
   inset:0 只是撑出了"视觉上"的高度，CSS 百分比高度解析认的是父元素显式声明的 height 属性
   （不认 inset 撑出来的隐式高度）——不显式补一个 height:100%，el-carousel 的 height="100%"
   会算成 0，导致轮播完全不可见（只剩兜底渐变）。 */
.bg-carousel {
  position: absolute;
  inset: 0;
  height: 100%;
  width: 100%;
  z-index: 0;
  background: linear-gradient(135deg, #1f2937, #4b5563);
}

/* el-carousel 组件本身只把 height="100%" 这个 prop 透传给 .el-carousel__container，根节点
   .el-carousel 自己不带任何高度样式——即便父级 .bg-carousel 有了显式高度，根节点 auto 高度
   在只有一个百分比高度子节点的情况下仍会被解析为 0（子节点撑不起 auto 高度的父节点），
   容器和 .el-carousel__item 的 100% 高度就跟着全部塌成 0。显式补上根节点高度打破这层循环。 */
.bg-carousel :deep(.el-carousel) {
  height: 100%;
}

/* 不做 Ken Burns 缩放动画：浏览器对背景层是先光栅化再按 transform 拉伸，慢速放大过程中
   图片必然发虚；保持 1:1 的 cover 展示才是最清晰的状态（图片清晰度要求见管理页提示）。 */
.bg-slide {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
}

/* 深色渐变遮罩：保证登录卡片和页脚文字在任意风景图上都有足够对比度可读 */
.bg-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.55) 0%, rgba(15, 23, 42, 0.35) 50%, rgba(15, 23, 42, 0.65) 100%);
}

.login-card {
  position: relative;
  z-index: 1;
  width: 360px;
  margin-bottom: auto;
  margin-top: auto;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(6px);
}

/* 暗色模式下毛玻璃卡片跟随变深，与内部表单控件的暗色保持一致 */
html.dark .login-card {
  background: rgba(20, 20, 20, 0.88);
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

/* FooterCopyright 是子组件，scoped 样式默认不穿透，显式提到背景遮罩之上 */
.login-page :deep(.footer-copyright) {
  position: relative;
  z-index: 1;
}
</style>
