<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { fetchCaptcha, register, sendRegisterEmailCode } from '@/api/auth'
import type { RegisterOptionsVO } from '@/types/api'
import {
  buildEmailCodeRequestPayload,
  buildRegistrationPayload,
  calculateCountdownSeconds,
  getEmailCodeRequestValidationFields,
  getRegisterStepPresentationFields,
  getRegisterVerificationPlan,
  getRegistrationStepValidationFields,
  isCaptchaConsumedByEmailCodeRequest,
  REGISTER_ACCOUNT_STEP,
  REGISTER_SECURITY_STEP,
  resolveEmailCodeCooldownSeconds,
  shouldHandleRegisterEnter,
  shouldClearEmailCodeAfterRegistrationFailure,
  type RegisterFormData,
  type RegisterStep,
} from './loginPageModel'

const props = defineProps<{
  options: RegisterOptionsVO
  primaryTextColor: string
}>()

const emit = defineEmits<{
  back: []
  complete: [username: string]
  'request-scroll-top': []
}>()

const formRef = ref<FormInstance>()
const step = ref<RegisterStep>(REGISTER_ACCOUNT_STEP)
const state = ref<'form' | 'success'>('form')
const submittedUsername = ref('')
const navigationPending = ref(false)
const submitting = ref(false)
const captchaImage = ref('')
const captchaLoading = ref(false)
const emailCodeSending = ref(false)
const emailCodeCountdown = ref(0)
const issuedEmail = ref('')
let emailCodeTimer: ReturnType<typeof setInterval> | undefined
let emailCodeCooldownDeadline = 0
let disposed = false
let captchaRequestGeneration = 0
let emailCodeRequestGeneration = 0
let registerRequestGeneration = 0

const form = reactive<RegisterFormData>({
  username: '',
  nickname: '',
  email: '',
  password: '',
  confirmPassword: '',
  captchaId: '',
  captcha: '',
  emailCode: '',
})

const verificationPlan = computed(() => getRegisterVerificationPlan(props.options))
const showCaptcha = computed(() => verificationPlan.value.presentationFields.includes('captcha'))
const captchaUsedForEmailCode = computed(() => (
  isCaptchaConsumedByEmailCodeRequest(verificationPlan.value)
))
const operationPending = computed(() => (
  navigationPending.value
  || submitting.value
  || emailCodeSending.value
))
const verificationRequestPending = computed(() => operationPending.value || captchaLoading.value)
const registrationPending = computed(() => operationPending.value || captchaLoading.value)
const emailCodeCooldownSeconds = computed(() => (
  resolveEmailCodeCooldownSeconds(props.options.emailCodeCooldownSeconds)
))

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3 至 32 位', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9._-]+$/, message: '仅支持字母、数字、点、下划线和短横线', trigger: 'blur' },
  ],
  nickname: [{ max: 64, message: '昵称不能超过 64 位', trigger: 'blur' }],
  email: [
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(new Error('请输入邮箱'))
          return
        }
        callback(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) ? undefined : new Error('邮箱格式不正确'))
      },
      trigger: 'blur',
    },
  ],
  captcha: [
    {
      validator: (_rule, value, callback) => {
        callback(showCaptcha.value && !value ? new Error('请输入图形验证码') : undefined)
      },
      trigger: 'blur',
    },
  ],
  emailCode: [
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(new Error('请输入邮箱验证码'))
          return
        }
        callback(issuedEmail.value === form.email
          ? undefined
          : new Error('请先向当前邮箱发送验证码'))
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度为 8 至 64 位', trigger: 'blur' },
    {
      // 与后端 PasswordPolicy 保持同一条基础规则，弱口令的最终判定仍由服务端负责。
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '密码需同时包含字母与数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value === form.password ? undefined : new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
}

function clearEmailCodeTimer() {
  if (emailCodeTimer) {
    clearInterval(emailCodeTimer)
    emailCodeTimer = undefined
  }
}

function clearIssuedEmailCode() {
  issuedEmail.value = ''
  form.emailCode = ''
  if (!disposed) {
    formRef.value?.clearValidate('emailCode')
  }
}

function clearEmailVerificationState() {
  clearIssuedEmailCode()
  emailCodeCooldownDeadline = 0
  emailCodeCountdown.value = 0
  clearEmailCodeTimer()
}

function clearCaptchaChallenge() {
  form.captchaId = ''
  form.captcha = ''
  captchaImage.value = ''
  if (!disposed) {
    formRef.value?.clearValidate('captcha')
  }
}

function clearRegistrationData() {
  form.username = ''
  form.nickname = ''
  form.email = ''
  form.password = ''
  form.confirmPassword = ''
  clearCaptchaChallenge()
  clearEmailVerificationState()
}

function updateEmailCodeCountdown() {
  if (disposed) {
    return
  }
  emailCodeCountdown.value = calculateCountdownSeconds(emailCodeCooldownDeadline, Date.now())
  if (emailCodeCountdown.value === 0) {
    emailCodeCooldownDeadline = 0
    clearEmailCodeTimer()
  }
}

function startEmailCodeCountdown() {
  clearEmailCodeTimer()
  emailCodeCooldownDeadline = Date.now() + emailCodeCooldownSeconds.value * 1000
  updateEmailCodeCountdown()
  if (emailCodeCountdown.value > 0 && !disposed) {
    emailCodeTimer = setInterval(updateEmailCodeCountdown, 1000)
  }
}

async function scrollToTop() {
  await nextTick()
  if (!disposed) {
    emit('request-scroll-top')
  }
}

async function refreshCaptcha() {
  if (disposed || !showCaptcha.value || captchaLoading.value) {
    return
  }
  const generation = ++captchaRequestGeneration
  captchaLoading.value = true
  try {
    const challenge = await fetchCaptcha()
    if (disposed || generation !== captchaRequestGeneration) {
      return
    }
    form.captchaId = challenge.captchaId
    form.captcha = ''
    captchaImage.value = challenge.image
    formRef.value?.clearValidate('captcha')
  } catch {
    // 请求层已展示服务端错误；保留可重试入口，不把异步异常泄漏到 Vue 事件循环。
    if (!disposed && generation === captchaRequestGeneration) {
      clearCaptchaChallenge()
    }
  } finally {
    if (!disposed && generation === captchaRequestGeneration) {
      captchaLoading.value = false
    }
  }
}

async function validateFields(fields: readonly string[]) {
  if (!formRef.value) {
    return false
  }
  const results = await Promise.all(fields.map((field) => (
    formRef.value!.validateField(field).then(() => true).catch(() => false)
  )))
  return !disposed && results.every(Boolean)
}

async function goToSecurityStep() {
  if (disposed || operationPending.value) {
    return
  }
  navigationPending.value = true
  try {
    const valid = await validateFields(getRegisterStepPresentationFields(
      REGISTER_ACCOUNT_STEP,
      verificationPlan.value,
    ))
    if (!valid || disposed) {
      return
    }
    step.value = REGISTER_SECURITY_STEP
    await scrollToTop()
  } finally {
    if (!disposed) {
      navigationPending.value = false
    }
  }
}

async function goToAccountStep() {
  if (disposed || operationPending.value) {
    return
  }
  navigationPending.value = true
  try {
    step.value = REGISTER_ACCOUNT_STEP
    await scrollToTop()
  } finally {
    if (!disposed) {
      navigationPending.value = false
    }
  }
}

function backToLogin() {
  if (!operationPending.value) {
    clearRegistrationData()
    emit('back')
  }
}

async function handleSendEmailCode() {
  if (disposed || verificationRequestPending.value || emailCodeCountdown.value > 0) {
    return
  }
  emailCodeSending.value = true
  const generation = ++emailCodeRequestGeneration
  try {
    const valid = await validateFields(getEmailCodeRequestValidationFields(verificationPlan.value))
    if (!valid || disposed || generation !== emailCodeRequestGeneration) {
      return
    }
    const payload = buildEmailCodeRequestPayload(form, verificationPlan.value)
    const ttlSeconds = await sendRegisterEmailCode(payload)
    if (disposed || generation !== emailCodeRequestGeneration || form.email !== payload.email) {
      return
    }
    issuedEmail.value = payload.email
    form.emailCode = ''
    formRef.value?.clearValidate('emailCode')
    if (captchaUsedForEmailCode.value) {
      // 图形码是一次性挑战；发码成功后丢弃旧凭据，确需重发时再由用户获取新挑战。
      clearCaptchaChallenge()
    }
    ElMessage.success(`验证码已发送，${Math.max(1, Math.round(ttlSeconds / 60))} 分钟内有效`)
    startEmailCodeCountdown()
  } catch {
    if (!disposed) {
      // 发码失败后服务端可能已使旧邮箱码失效；只清空凭据，保留既有发码冷却。
      clearIssuedEmailCode()
      // 图形码一次性消费；发码失败后清空旧挑战，由用户按需重新获取。
      if (isCaptchaConsumedByEmailCodeRequest(verificationPlan.value)) {
        clearCaptchaChallenge()
      }
    }
  } finally {
    if (!disposed) {
      emailCodeSending.value = false
    }
  }
}

async function handleRegister() {
  if (disposed || registrationPending.value) {
    return
  }
  submitting.value = true
  const generation = ++registerRequestGeneration
  try {
    // 最终提交重新覆盖两步字段；邮箱模式不再校验发码时已消费的图形码。
    const accountValid = await validateFields(getRegistrationStepValidationFields(
      REGISTER_ACCOUNT_STEP,
      verificationPlan.value,
    ))
    if (!accountValid || disposed || generation !== registerRequestGeneration) {
      if (!disposed) {
        step.value = REGISTER_ACCOUNT_STEP
        await scrollToTop()
      }
      return
    }
    const securityValid = await validateFields(getRegistrationStepValidationFields(
      REGISTER_SECURITY_STEP,
      verificationPlan.value,
    ))
    if (!securityValid || disposed || generation !== registerRequestGeneration) {
      return
    }
    const payload = buildRegistrationPayload(form, verificationPlan.value)
    const username = form.username
    await register(payload)
    if (disposed || generation !== registerRequestGeneration) {
      return
    }
    submittedUsername.value = username
    // 成功页只保留展示所需用户名，立即缩短密码与一次性验证码在内存中的暴露窗口。
    clearRegistrationData()
    state.value = 'success'
    clearEmailCodeTimer()
    emailCodeCountdown.value = 0
    await scrollToTop()
  } catch (error) {
    // 只有已通过验证码核验的冲突或结果未知的异常才按“已消费”收敛；前置校验失败与普通输错允许原地修正。
    // 图形码不在这一步消费（它只用于发码），失败后无需清空。
    if (!disposed && shouldClearEmailCodeAfterRegistrationFailure(error)) {
      clearIssuedEmailCode()
    }
  } finally {
    if (!disposed && generation === registerRequestGeneration) {
      submitting.value = false
    }
  }
}

function handleEnter(event: KeyboardEvent) {
  const targetInsideButton = Boolean((event.target as HTMLElement | null)?.closest('button'))
  if (!shouldHandleRegisterEnter(event, targetInsideButton, operationPending.value)) {
    return
  }
  event.preventDefault()
  if (step.value === REGISTER_ACCOUNT_STEP) {
    void goToSecurityStep()
    return
  }
  void handleRegister()
}

function completeRegistration() {
  if (!operationPending.value) {
    emit('complete', submittedUsername.value)
  }
}

onMounted(refreshCaptcha)
watch(() => form.email, () => {
  emailCodeRequestGeneration += 1
  clearEmailVerificationState()
}, { flush: 'sync' })
onBeforeUnmount(() => {
  disposed = true
  captchaRequestGeneration += 1
  emailCodeRequestGeneration += 1
  registerRequestGeneration += 1
  clearRegistrationData()
})
</script>

<template>
  <section
    class="register-panel"
    aria-labelledby="register-title"
    :style="{ '--login-primary-text': primaryTextColor }"
  >
    <template v-if="state === 'form'">
      <div class="panel-heading register-panel-heading">
        <div>
          <p class="eyebrow">创建本地账号</p>
          <h1 id="register-title">加入智能体运营台</h1>
          <p>注册需验证邮箱：我们会向你填写的邮箱发送验证码。完成注册后即可登录查看审核状态，管理员审核并分配角色后需重新登录。</p>
        </div>
        <el-button class="back-login" link :disabled="operationPending" @click="backToLogin">返回登录</el-button>
      </div>

      <ol class="register-steps" aria-label="注册步骤">
        <li
          :class="{ active: step === REGISTER_ACCOUNT_STEP, complete: step === REGISTER_SECURITY_STEP }"
          :aria-current="step === REGISTER_ACCOUNT_STEP ? 'step' : undefined"
        >
          <span>1</span>
          <div><strong>账号资料</strong><small>建立身份信息</small></div>
        </li>
        <li
          :class="{ active: step === REGISTER_SECURITY_STEP }"
          :aria-current="step === REGISTER_SECURITY_STEP ? 'step' : undefined"
        >
          <span>2</span>
          <div><strong>安全验证</strong><small>设置登录凭据</small></div>
        </li>
      </ol>

      <el-form
        ref="formRef"
        class="register-form"
        :model="form"
        :rules="rules"
        :disabled="operationPending"
        :aria-busy="operationPending"
        @keydown.enter="handleEnter"
        @submit.prevent
      >
        <!-- v-show 保留两步字段注册；最终提交按后端实际消费字段重新校验。 -->
        <div v-show="step === REGISTER_ACCOUNT_STEP" class="form-step" aria-labelledby="register-account-step">
          <h2 id="register-account-step" class="sr-only">账号资料</h2>
          <label class="field-label" for="register-username">用户名 <span>必填</span></label>
          <el-form-item prop="username">
            <el-input id="register-username" v-model="form.username" size="large" autocomplete="username" placeholder="3-32 位，支持字母、数字及 . _ -" :prefix-icon="'User'" />
          </el-form-item>

          <label class="field-label" for="register-nickname">昵称 <span>选填</span></label>
          <el-form-item prop="nickname">
            <el-input id="register-nickname" v-model="form.nickname" size="large" autocomplete="name" placeholder="你希望被如何称呼" :prefix-icon="'Postcard'" />
          </el-form-item>

          <label class="field-label" for="register-email">邮箱 <span>必填</span></label>
          <el-form-item prop="email">
            <el-input id="register-email" v-model="form.email" size="large" autocomplete="email" placeholder="用于接收注册验证码与审核结果" :prefix-icon="'Message'" />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="primary-action"
            :disabled="operationPending"
            @click="goToSecurityStep"
          >
            下一步
          </el-button>
        </div>

        <div v-show="step === REGISTER_SECURITY_STEP" class="form-step" aria-labelledby="register-security-step">
          <h2 id="register-security-step" class="sr-only">安全验证</h2>

          <template v-if="showCaptcha">
            <label class="field-label" for="register-captcha">
              图形验证码 <span>{{ captchaUsedForEmailCode ? '发码/重发时必填' : '必填' }}</span>
            </label>
            <el-form-item prop="captcha">
              <div class="verification-row">
                <el-input id="register-captcha" v-model="form.captcha" size="large" maxlength="16" autocomplete="off" placeholder="请输入图中字符" :prefix-icon="'Key'" />
                <button v-if="captchaImage" type="button" class="captcha-challenge" aria-label="刷新图形验证码" :disabled="verificationRequestPending" @click="refreshCaptcha">
                  <img :src="captchaImage" alt="图形验证码" />
                  <span>换一张</span>
                </button>
                <el-button v-else class="captcha-fallback" size="large" :loading="captchaLoading" @click="refreshCaptcha">获取验证码</el-button>
              </div>
            </el-form-item>
          </template>

          <label class="field-label" for="register-email-code">邮箱验证码 <span>必填</span></label>
          <el-form-item prop="emailCode">
            <div class="verification-row">
              <el-input id="register-email-code" v-model="form.emailCode" size="large" maxlength="16" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入邮件中的验证码" :prefix-icon="'Message'" />
              <el-button class="email-code-button" size="large" :loading="emailCodeSending" :disabled="verificationRequestPending || emailCodeCountdown > 0" @click="handleSendEmailCode">
                {{ emailCodeCountdown > 0 ? `${emailCodeCountdown} 秒后重发` : '发送验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <label class="field-label" for="register-password">密码 <span>必填</span></label>
          <el-form-item prop="password">
            <el-input id="register-password" v-model="form.password" type="password" size="large" show-password autocomplete="new-password" placeholder="至少 8 位，同时包含字母与数字" :prefix-icon="'Lock'" />
          </el-form-item>

          <label class="field-label" for="register-confirm-password">确认密码 <span>必填</span></label>
          <el-form-item prop="confirmPassword">
            <el-input id="register-confirm-password" v-model="form.confirmPassword" type="password" size="large" show-password autocomplete="new-password" placeholder="再次输入登录密码" :prefix-icon="'Lock'" />
          </el-form-item>

          <div class="step-actions">
            <el-button size="large" :disabled="operationPending" @click="goToAccountStep">上一步</el-button>
            <el-button
              type="primary"
              size="large"
              class="primary-action"
              :loading="submitting"
              :disabled="registrationPending"
              @click="handleRegister"
            >
              提交注册
            </el-button>
          </div>
        </div>
      </el-form>
    </template>

    <div v-else class="registration-success" aria-live="polite">
      <div class="success-mark" aria-hidden="true"><el-icon><CircleCheckFilled /></el-icon></div>
      <p class="eyebrow">账号已创建</p>
      <h1 id="register-title">注册成功，{{ submittedUsername }}</h1>
      <p>现在可以登录查看审核状态。管理员完成审核并分配角色后，请重新登录以加载已开通菜单。</p>
      <el-button
        type="primary"
        size="large"
        class="primary-action"
        :disabled="operationPending"
        @click="completeRegistration"
      >
        返回登录
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.register-panel,
.register-form,
.form-step {
  width: 100%;
}

.panel-heading {
  margin-bottom: 22px;
}

.register-panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.eyebrow {
  margin: 0 0 8px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: clamp(26px, 2.2vw, 34px);
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.panel-heading p:last-child,
.registration-success > p:last-of-type {
  max-width: 460px;
  margin: 10px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.65;
}

.back-login {
  flex: none;
  margin-top: 20px;
}

.register-steps {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 0 0 22px;
  padding: 0;
  list-style: none;
}

.register-steps li {
  display: grid;
  grid-template-columns: 30px 1fr;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-secondary);
}

.register-steps li > span {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 700;
}

.register-steps li.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
}

.register-steps li.active > span,
.register-steps li.complete > span {
  background: var(--theme-primary-solid, var(--el-color-primary));
  color: var(--login-primary-text, #fff);
}

.register-steps strong,
.register-steps small {
  display: block;
}

.register-steps strong {
  font-size: 13px;
  line-height: 1.25;
}

.register-steps small {
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.field-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 650;
}

.field-label span {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  font-weight: 500;
}

.register-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.register-form :deep(.el-input__wrapper) {
  min-height: 44px;
}

.verification-row {
  display: flex;
  align-items: stretch;
  gap: 10px;
  width: 100%;
}

.captcha-challenge {
  width: 124px;
  min-height: 44px;
  flex: none;
  position: relative;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
  cursor: pointer;
}

.captcha-challenge img {
  width: 100%;
  height: 100%;
  min-height: 42px;
  display: block;
  object-fit: cover;
}

.captcha-challenge span {
  position: absolute;
  right: 4px;
  bottom: 3px;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgba(11, 23, 40, 0.78);
  color: #fff;
  font-size: 10px;
}

.captcha-fallback,
.email-code-button {
  min-width: 124px;
  flex: none;
}

.step-actions {
  display: grid;
  grid-template-columns: 112px 1fr;
  gap: 10px;
}

.registration-success {
  width: 100%;
  padding: 18px 0;
}

.success-mark {
  width: 54px;
  height: 54px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 22px;
  border-radius: 16px;
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
  font-size: 28px;
}

.registration-success .primary-action {
  margin-top: 28px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

@media (max-width: 480px) {
  .register-panel-heading {
    gap: 10px;
  }

  .register-steps small {
    display: none;
  }

  .verification-row {
    gap: 8px;
  }

  .captcha-challenge,
  .captcha-fallback,
  .email-code-button {
    min-width: 108px;
    width: 108px;
    padding-inline: 8px;
  }
}
</style>
