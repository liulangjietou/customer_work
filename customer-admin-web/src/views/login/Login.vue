<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { fetchRegisterOptions, login, ssoLogin } from '@/api/auth'
import { fetchLoginCarouselUrls } from '@/api/login-image'
import FooterCopyright from '@/components/FooterCopyright.vue'
import { useAuthStore } from '@/store/auth'
import { useMenuStore } from '@/store/menu'
import { useThemeStore } from '@/store/theme'
import type { LoginResponse, RegisterOptionsVO } from '@/types/api'
import { chooseButtonTextColor } from '@/utils/themeContrast'
import LoginBrandStage from './LoginBrandStage.vue'
import RegisterPanel from './RegisterPanel.vue'
import {
  createLoginSubmission,
  getLoginModePresentation,
  shouldShowRegisterEntry,
  type LoginMode,
} from './loginPageModel'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const menuStore = useMenuStore()
const themeStore = useThemeStore()

const authSurfaceRef = ref<HTMLElement>()
const authPanel = ref<'login' | 'register'>('login')
const loginMode = ref<LoginMode>('local')
const modePresentation = computed(() => getLoginModePresentation(loginMode.value))
const localModePresentation = getLoginModePresentation('local')
const ssoModePresentation = getLoginModePresentation('sso')
const primaryTextColor = computed(() => chooseButtonTextColor(themeStore.primaryColor))

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '', rememberMe: false })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// 注册能力必须由匿名接口成功确认；接口未返回时按关闭处理，避免先展示再被服务端拒绝。
const registerOptionsLoaded = ref(false)
const registerOptions = ref<RegisterOptionsVO>({
  selfServiceEnabled: false,
  captchaRequired: false,
  emailRequired: false,
  emailVerificationRequired: false,
})
const canRegister = computed(() => shouldShowRegisterEntry(
  loginMode.value,
  registerOptionsLoaded.value,
  registerOptions.value.selfServiceEnabled,
))

// 后台仍可配置多张登录图；接口不可用时用首页同源客服视觉兜底。
const bgImages = ref<string[]>(['/home-cover.jpg'])

const REMEMBER_KEY_PREFIX = 'admin-remember-username-'

function loadRememberedUsername(mode: LoginMode) {
  const saved = localStorage.getItem(REMEMBER_KEY_PREFIX + mode)
  form.username = saved || ''
  form.rememberMe = Boolean(saved)
}

async function resetAuthScroll() {
  await nextTick()
  if (authSurfaceRef.value) {
    authSurfaceRef.value.scrollTop = 0
  }
  const documentScroller = document.scrollingElement
  if (documentScroller) {
    documentScroller.scrollTop = 0
  }
}

async function switchMode(mode: LoginMode) {
  if (submitting.value || loginMode.value === mode) {
    return
  }
  loginMode.value = mode
  form.password = ''
  loadRememberedUsername(mode)
  formRef.value?.clearValidate()
  await resetAuthScroll()
}

async function handleModeKeydown(event: KeyboardEvent) {
  if (submitting.value) {
    return
  }
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
    return
  }
  event.preventDefault()
  const nextMode: LoginMode = event.key === 'ArrowLeft' || event.key === 'Home' ? 'local' : 'sso'
  await switchMode(nextMode)
  await nextTick()
  document.getElementById(`login-mode-${nextMode}`)?.focus()
}

async function openRegister() {
  if (submitting.value || !canRegister.value) {
    return
  }
  authPanel.value = 'register'
  await resetAuthScroll()
}

async function backToLogin(username?: string) {
  authPanel.value = 'login'
  loginMode.value = 'local'
  form.password = ''
  if (username) {
    form.username = username
    form.rememberMe = false
  } else {
    loadRememberedUsername('local')
  }
  formRef.value?.clearValidate()
  await resetAuthScroll()
}

async function loadLoginImages() {
  try {
    const urls = await fetchLoginCarouselUrls()
    if (urls.length > 0) {
      bgImages.value = urls
    }
  } catch {
    // 后端不可用时静默使用内置图，不干扰身份验证主流程。
  }
}

async function loadRegisterOptions() {
  try {
    registerOptions.value = await fetchRegisterOptions()
    registerOptionsLoaded.value = true
  } catch {
    registerOptionsLoaded.value = false
  }
}

async function handleSubmit() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) {
      return
    }
    // 请求发出后，界面仍可能因脚本触发变化；后续处理必须绑定本次提交身份。
    const submission = createLoginSubmission(loginMode.value, form)
    let result: LoginResponse
    try {
      result = submission.mode === 'local'
        ? await login(submission.credentials)
        : await ssoLogin(submission.credentials)
    } catch {
      // 请求层已展示业务或网络错误，组件只负责恢复可提交状态。
      return
    }
    const rememberKey = REMEMBER_KEY_PREFIX + submission.mode
    if (submission.credentials.rememberMe) {
      localStorage.setItem(rememberKey, submission.credentials.username)
    } else {
      localStorage.removeItem(rememberKey)
    }
    // 必须应用完整登录结果，保留昵称、强制改密与账号审核状态。
    auth.applyLoginResult(result, submission.credentials.username)
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

loadRememberedUsername(loginMode.value)

onMounted(() => {
  // 图片与注册能力互不依赖，并行加载，慢图片不会阻塞注册入口判断。
  void Promise.allSettled([loadLoginImages(), loadRegisterOptions()])
})
</script>

<template>
  <main class="login-page">
    <LoginBrandStage :images="bgImages" />

    <section ref="authSurfaceRef" class="auth-surface" data-login-scroll aria-label="身份验证">
      <div class="auth-toolbar">
        <span class="secure-context"><i aria-hidden="true" /> 安全登录</span>
        <el-button
          circle
          plain
          :icon="themeStore.isDark ? 'Sunny' : 'Moon'"
          :aria-label="themeStore.isDark ? '切换到亮色模式' : '切换到暗色模式'"
          :title="themeStore.isDark ? '切换到亮色模式' : '切换到暗色模式'"
          @click="themeStore.toggleDark()"
        />
      </div>

      <div class="auth-content">
        <section
          v-if="authPanel === 'login'"
          id="login-panel"
          class="auth-panel"
          role="tabpanel"
          :aria-labelledby="`login-mode-${loginMode}`"
        >
          <header class="login-heading">
            <p class="eyebrow">customer_work · Agent Console</p>
            <h1>{{ modePresentation.title }}</h1>
            <p>{{ modePresentation.description }}</p>
          </header>

          <div class="mode-switch" role="tablist" aria-label="登录方式">
            <button
              id="login-mode-local"
              type="button"
              role="tab"
              :class="{ active: loginMode === 'local' }"
              :aria-selected="loginMode === 'local'"
              :disabled="submitting"
              aria-controls="login-panel"
              :tabindex="loginMode === 'local' ? 0 : -1"
              @click="switchMode('local')"
              @keydown="handleModeKeydown"
            >
              {{ localModePresentation.label }}
            </button>
            <button
              id="login-mode-sso"
              type="button"
              role="tab"
              :class="{ active: loginMode === 'sso' }"
              :aria-selected="loginMode === 'sso'"
              :disabled="submitting"
              aria-controls="login-panel"
              :tabindex="loginMode === 'sso' ? 0 : -1"
              @click="switchMode('sso')"
              @keydown="handleModeKeydown"
            >
              {{ ssoModePresentation.label }}
            </button>
          </div>

          <el-form ref="formRef" class="login-form" :model="form" :rules="rules" @submit.prevent="handleSubmit">
            <label class="field-label" for="login-username">{{ modePresentation.usernameLabel }}</label>
            <el-form-item prop="username">
              <el-input
                id="login-username"
                v-model="form.username"
                size="large"
                autocomplete="username"
                :placeholder="modePresentation.usernamePlaceholder"
                :prefix-icon="'User'"
                :disabled="submitting"
              />
            </el-form-item>

            <label class="field-label" for="login-password">{{ modePresentation.passwordLabel }}</label>
            <el-form-item prop="password">
              <el-input
                id="login-password"
                v-model="form.password"
                type="password"
                size="large"
                show-password
                autocomplete="current-password"
                :placeholder="modePresentation.passwordPlaceholder"
                :prefix-icon="'Lock'"
                :disabled="submitting"
              />
            </el-form-item>

            <div class="remember-row">
              <el-checkbox v-model="form.rememberMe" :disabled="submitting">保持登录</el-checkbox>
              <span>延长登录状态；本机仅保存用户名</span>
            </div>

            <el-button
              native-type="submit"
              type="primary"
              size="large"
              class="primary-action"
              :style="{ '--login-primary-text': primaryTextColor }"
              :loading="submitting"
            >
              {{ loginMode === 'local' ? '进入运营台' : '使用 OA 账号登录' }}
            </el-button>
          </el-form>

          <p v-if="loginMode === 'sso'" class="mode-note">
            OA 身份由企业目录校验，首次登录会关联后台身份。
          </p>

          <div v-if="canRegister" class="register-entry">
            <span>还没有本地账号？</span>
            <el-button link type="primary" :disabled="submitting" @click="openRegister">创建账号</el-button>
            <small>注册后由管理员审核并分配菜单权限</small>
          </div>
        </section>

        <RegisterPanel
          v-else
          class="auth-panel"
          :options="registerOptions"
          :primary-text-color="primaryTextColor"
          @back="backToLogin()"
          @complete="backToLogin"
          @request-scroll-top="resetAuthScroll"
        />
      </div>

      <FooterCopyright />
    </section>
  </main>
</template>

<style scoped>
.login-page {
  width: 100%;
  height: 100%;
  min-height: 560px;
  display: grid;
  grid-template-columns: minmax(0, 58fr) minmax(500px, 42fr);
  overflow: hidden;
  background: var(--el-bg-color-page);
}

.auth-surface {
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  overflow-y: auto;
  background:
    radial-gradient(circle at 100% 0%, var(--el-color-primary-light-9) 0%, transparent 31%),
    var(--el-bg-color);
  color: var(--el-text-color-primary);
  scrollbar-gutter: stable;
}

.auth-toolbar {
  min-height: var(--cw-topbar-height, 64px);
  display: flex;
  flex: none;
  align-items: center;
  justify-content: flex-end;
  gap: 18px;
  box-sizing: border-box;
  padding: 12px clamp(24px, 3vw, 42px) 0;
}

.secure-context {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.secure-context i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-success);
  box-shadow: 0 0 0 4px var(--el-color-success-light-9);
}

.auth-content {
  width: 100%;
  min-height: 0;
  flex: 1 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  padding: 26px clamp(34px, 4.2vw, 64px) 30px;
}

.auth-panel {
  width: 100%;
  max-width: 440px;
  margin-block: auto;
}

.login-heading {
  margin-bottom: 26px;
}

.eyebrow {
  margin: 0 0 9px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.login-heading h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: clamp(30px, 2.5vw, 38px);
  line-height: 1.2;
  letter-spacing: -0.03em;
}

.login-heading > p:last-child {
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-bottom: 24px;
  padding: 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 11px;
  background: var(--el-fill-color-extra-light);
}

.mode-switch button {
  min-height: 38px;
  padding: 0 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--el-text-color-secondary);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition: background-color 160ms ease-out, color 160ms ease-out, box-shadow 160ms ease-out;
}

.mode-switch button.active {
  background: var(--el-bg-color);
  color: var(--el-color-primary);
  box-shadow: var(--cw-card-shadow);
}

.mode-switch button:disabled {
  cursor: wait;
}

.field-label {
  display: block;
  margin-bottom: 7px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 650;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 46px;
}

.remember-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin: -2px 0 20px;
}

.remember-row span {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.auth-panel :deep(.primary-action) {
  width: 100%;
  min-height: 46px;
  --el-button-text-color: var(--login-primary-text);
  --el-button-hover-text-color: var(--login-primary-text);
  --el-button-active-text-color: var(--login-primary-text);
}

.mode-note {
  margin: 14px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.register-entry {
  display: grid;
  grid-template-columns: auto auto;
  justify-content: center;
  align-items: center;
  column-gap: 4px;
  margin-top: 21px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.register-entry small {
  grid-column: 1 / -1;
  margin-top: 3px;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  text-align: center;
}

@media (max-width: 1200px) and (min-width: 1024px) {
  .login-page {
    grid-template-columns: minmax(0, 54fr) minmax(480px, 46fr);
  }

  .auth-content {
    padding-inline: 30px;
  }
}

@media (min-width: 900px) and (max-height: 720px) {
  .auth-toolbar {
    min-height: 52px;
  }

  .auth-content {
    padding-block: 16px 18px;
  }

  .login-heading {
    margin-bottom: 18px;
  }

  .mode-switch {
    margin-bottom: 18px;
  }
}

@media (max-width: 899px) {
  .login-page {
    height: auto;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    overflow: visible;
  }

  .auth-surface {
    height: auto;
    min-height: calc(100svh - 210px);
    overflow: visible;
    scrollbar-gutter: auto;
  }

  .auth-toolbar {
    min-height: 54px;
    padding: 10px 24px 0;
  }

  .auth-content {
    min-height: auto;
    align-items: flex-start;
    padding: 18px 28px 34px;
  }

  .auth-panel {
    margin-block: 0;
  }
}

@media (max-width: 480px) {
  .secure-context {
    display: none;
  }

  .auth-toolbar {
    min-height: 48px;
    padding-inline: 20px;
  }

  .auth-content {
    padding: 14px 20px 30px;
  }

  .login-heading {
    margin-bottom: 20px;
  }

  .remember-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }
}
</style>
