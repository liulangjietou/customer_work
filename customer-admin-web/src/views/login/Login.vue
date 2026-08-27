<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { fetchCaptcha, fetchRegisterOptions, login, register, sendRegisterEmailCode, ssoLogin } from '@/api/auth'
import { fetchLoginCarouselUrls } from '@/api/login-image'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import FooterCopyright from '@/components/FooterCopyright.vue'
import type { RegisterOptionsVO, RegisterRequest } from '@/types/api'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()

const authPanel = ref<'login' | 'register'>('login')

/** local：账号密码登录（数据库） / sso：OA 域账号登录（LDAP/AD） */
const loginMode = ref<'local' | 'sso'>('local')

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '', rememberMe: false })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerFormRef = ref<FormInstance>()
const registerSubmitting = ref(false)
const registerForm = reactive<RegisterRequest>({
  username: '',
  nickname: '',
  email: '',
  password: '',
  confirmPassword: '',
  captchaId: '',
  captcha: '',
  emailCode: '',
})

// 本实例是否开放注册、是否要求验证码与邮箱：由后端按部署形态给出，
// 对外开放实例上这两项是强制的，前端不做本地判断（判断了也会被后端再拦一次）。
const registerOptions = ref<RegisterOptionsVO>({
  selfServiceEnabled: true,
  captchaRequired: false,
  emailRequired: false,
  emailVerificationRequired: false,
})
const captchaImage = ref('')
const captchaLoading = ref(false)

// 邮箱验证码：发码后按冷却时间倒计时，避免用户反复点击——每一次点击都是一封真实的邮件
const emailCodeSending = ref(false)
const emailCodeCountdown = ref(0)
let emailCodeTimer: ReturnType<typeof setInterval> | undefined

function startEmailCodeCountdown(seconds: number) {
  emailCodeCountdown.value = seconds
  clearEmailCodeTimer()
  emailCodeTimer = setInterval(() => {
    emailCodeCountdown.value -= 1
    if (emailCodeCountdown.value <= 0) {
      clearEmailCodeTimer()
    }
  }, 1000)
}

function clearEmailCodeTimer() {
  if (emailCodeTimer) {
    clearInterval(emailCodeTimer)
    emailCodeTimer = undefined
  }
}

// 定时器不随组件销毁自动停止，离开登录页后继续跑会一直持有闭包
onBeforeUnmount(clearEmailCodeTimer)

async function handleSendEmailCode() {
  // 只校验邮箱与图形码这两项：整表校验会因为密码还没填而拦下发码
  const emailValid = await registerFormRef.value?.validateField('email').then(() => true).catch(() => false)
  if (!emailValid) {
    return
  }
  if (registerOptions.value.captchaRequired && !registerForm.captcha) {
    ElMessage.warning('请先填写图形验证码')
    return
  }
  emailCodeSending.value = true
  try {
    const ttlSeconds = await sendRegisterEmailCode({
      email: registerForm.email as string,
      captchaId: registerForm.captchaId,
      captcha: registerForm.captcha,
    })
    ElMessage.success(`验证码已发送，${Math.round(ttlSeconds / 60)} 分钟内有效`)
    // 冷却按服务端的 60 秒走；这里只是不让用户空点，真正的限制在服务端
    startEmailCodeCountdown(60)
  } catch (error) {
    // 图形码是一次性的，无论因为什么失败都已作废，必须换一张
    await refreshCaptcha()
    throw error
  } finally {
    emailCodeSending.value = false
  }
}

async function refreshCaptcha() {
  if (!registerOptions.value.captchaRequired) {
    return
  }
  captchaLoading.value = true
  try {
    const challenge = await fetchCaptcha()
    registerForm.captchaId = challenge.captchaId
    registerForm.captcha = ''
    captchaImage.value = challenge.image
  } finally {
    captchaLoading.value = false
  }
}
const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3 至 32 位', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9._-]+$/, message: '仅支持字母、数字、点、下划线和短横线', trigger: 'blur' },
  ],
  nickname: [{ max: 64, message: '昵称不能超过 64 位', trigger: 'blur' }],
  captcha: [
    {
      validator: (_rule, value, callback) => {
        callback(registerOptions.value.captchaRequired && !value ? new Error('请输入图形验证码') : undefined)
      },
      trigger: 'blur',
    },
  ],
  emailCode: [
    {
      validator: (_rule, value, callback) => {
        callback(registerOptions.value.emailVerificationRequired && !value
          ? new Error('请输入邮箱验证码') : undefined)
      },
      trigger: 'blur',
    },
  ],
  email: [
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(registerOptions.value.emailRequired ? new Error('请输入邮箱') : undefined)
          return
        }
        callback(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) ? undefined : new Error('邮箱格式不正确'))
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度为 8 至 64 位', trigger: 'blur' },
    {
      // 与后端 PasswordPolicy 保持同一条规则：够长 + 字母数字混合。
      // 前端先拦一道只是为了少一次往返，真正的判定在服务端。
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '密码需同时包含字母与数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
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

async function openRegister() {
  authPanel.value = 'register'
  registerFormRef.value?.clearValidate()
  await refreshCaptcha()
}

function backToLogin() {
  authPanel.value = 'login'
  loginMode.value = 'local'
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
  try {
    registerOptions.value = await fetchRegisterOptions()
  } catch {
    // 拿不到部署形态时保持默认（开放注册、不强制验证码）：
    // 真正的强制在服务端，这里只影响表单要不要渲染验证码框
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

async function handleRegister() {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  registerSubmitting.value = true
  try {
    const username = registerForm.username
    await register(registerForm)
    ElMessage.success('注册成功，请登录后等待管理员审核')
    registerFormRef.value?.resetFields()
    clearEmailCodeTimer()
    emailCodeCountdown.value = 0
    backToLogin()
    form.username = username
    form.password = ''
  } catch (error) {
    // 开了邮箱验证时，图形码是在「获取验证码」那一步用掉的，注册失败与它无关；
    // 邮箱验证码也不刷新——用户名重复这类失败并不作废它，逼人重新收信只会多发一封邮件。
    // 没开邮箱验证时，图形码刚刚被这次提交消费掉，必须换一张，
    // 否则用户改完再提交会拿到一个"验证码错误"的二次失败。
    if (!registerOptions.value.emailVerificationRequired) {
      await refreshCaptcha()
    }
    throw error
  } finally {
    registerSubmitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="bg-carousel">
      <el-carousel height="100%" :interval="2000" arrow="never" indicator-position="none">
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
      <div class="access-flow" aria-label="账号开通流程">
        <span>注册账号</span>
        <i />
        <span>管理员审核</span>
        <i />
        <span>开通菜单</span>
      </div>
      <div v-if="authPanel === 'login'" class="login-tabs">
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
      <el-form
        v-if="authPanel === 'login'"
        ref="formRef"
        :model="form"
        :rules="rules"
        @keyup.enter="handleSubmit"
        @submit.prevent
      >
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
        <div v-if="loginMode === 'local' && registerOptions.selfServiceEnabled" class="panel-switch">
          还没有账号？<el-button link type="primary" @click="openRegister">立即注册</el-button>
        </div>
      </el-form>
      <el-form
        v-else
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        @keyup.enter="handleRegister"
        @submit.prevent
      >
        <div class="register-heading">
          <strong>注册本地账号</strong>
          <span>
            {{
              registerOptions.emailVerificationRequired
                ? '需先验证邮箱，注册后可登录查看审核状态，菜单将在管理员分配角色后开通。'
                : '注册后可登录查看审核状态，菜单将在管理员分配角色后开通。'
            }}
          </span>
        </div>
        <el-form-item prop="username">
          <el-input
            v-model="registerForm.username"
            placeholder="用户名（3-32 位）"
            size="large"
            autocomplete="username"
            :prefix-icon="'User'"
          />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input
            v-model="registerForm.nickname"
            placeholder="昵称（选填）"
            size="large"
            autocomplete="name"
            :prefix-icon="'Postcard'"
          />
        </el-form-item>
        <el-form-item prop="email">
          <el-input
            v-model="registerForm.email"
            :placeholder="registerOptions.emailRequired ? '邮箱（接收审核结果）' : '邮箱（选填）'"
            size="large"
            autocomplete="email"
            :prefix-icon="'Message'"
          />
        </el-form-item>
        <el-form-item v-if="registerOptions.captchaRequired" prop="captcha">
          <div class="captcha-row">
            <el-input
              v-model="registerForm.captcha"
              placeholder="图形验证码"
              size="large"
              maxlength="8"
              autocomplete="off"
              :prefix-icon="'Key'"
            />
            <!-- 点击图片换一张：图形码是一次性的，看不清时不该逼人重填整张表 -->
            <img
              v-if="captchaImage"
              class="captcha-image"
              :src="captchaImage"
              alt="点击刷新验证码"
              title="点击刷新"
              @click="refreshCaptcha"
            />
            <el-button v-else size="large" :loading="captchaLoading" @click="refreshCaptcha">
              获取
            </el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="registerOptions.emailVerificationRequired" prop="emailCode">
          <div class="captcha-row">
            <el-input
              v-model="registerForm.emailCode"
              placeholder="邮箱验证码"
              size="large"
              maxlength="8"
              autocomplete="one-time-code"
              :prefix-icon="'Message'"
            />
            <!-- 倒计时期间禁用：每一次点击都是一封真实的邮件，服务端还有 60 秒冷却与每日总量 -->
            <el-button
              class="email-code-button"
              size="large"
              :loading="emailCodeSending"
              :disabled="emailCodeCountdown > 0"
              @click="handleSendEmailCode"
            >
              {{ emailCodeCountdown > 0 ? `${emailCodeCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="registerForm.password"
            type="password"
            placeholder="密码（至少 8 位，含字母与数字）"
            size="large"
            show-password
            autocomplete="new-password"
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            size="large"
            show-password
            autocomplete="new-password"
            :prefix-icon="'Lock'"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            style="width: 100%"
            :loading="registerSubmitting"
            @click="handleRegister"
          >
            提交注册
          </el-button>
        </el-form-item>
        <div class="panel-switch">
          已有账号？<el-button link type="primary" @click="backToLogin">返回登录</el-button>
        </div>
      </el-form>
    </el-card>
    <FooterCopyright dark />
  </div>
</template>

<style scoped>
.captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
  align-items: center;
}

.email-code-button {
  flex-shrink: 0;
  min-width: 108px;
}

.captcha-image {
  height: 40px;
  width: 120px;
  border-radius: 4px;
  cursor: pointer;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color);
}

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

/* cover 铺满整屏不留空，与屏幕宽高比不一致的部分会被裁切（竖版图在横屏上会切掉上下）。
   不做 Ken Burns 缩放动画：背景层是先光栅化再按 transform 拉伸，慢速放大必然发虚。 */
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
  width: 390px;
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

.access-flow {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr auto;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.access-flow i {
  height: 1px;
  background: var(--el-border-color);
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

.register-heading {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 2px 0 16px;
}

.register-heading strong {
  color: var(--el-text-color-primary);
  font-size: 16px;
}

.register-heading span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.panel-switch {
  margin-top: -4px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

@media (max-width: 480px) {
  .login-card {
    width: calc(100% - 32px);
  }
}

/* FooterCopyright 是子组件，scoped 样式默认不穿透，显式提到背景遮罩之上 */
.login-page :deep(.footer-copyright) {
  position: relative;
  z-index: 1;
}
</style>
