<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { fetchCaptcha, resetPassword, sendPasswordResetEmailCode } from '@/api/auth'
import type { RegisterOptionsVO } from '@/types/api'
import {
  buildPasswordResetEmailCodePayload,
  buildPasswordResetPayload,
  calculateCountdownSeconds,
  PASSWORD_RESET_EMAIL_CODE_FIELDS,
  PASSWORD_RESET_SUBMIT_FIELDS,
  resolveEmailCodeCooldownSeconds,
  shouldHandleRegisterEnter,
  shouldKeepEmailCodeAfterPasswordResetFailure,
  type PasswordResetFormData,
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
const state = ref<'form' | 'success'>('form')
const submittedUsername = ref('')
const submitting = ref(false)
const captchaImage = ref('')
const captchaLoading = ref(false)
const emailCodeSending = ref(false)
const emailCodeCountdown = ref(0)
/** 已经向哪个「用户名+邮箱」组合发过码；两者任一改动，手里那份码就不再对应当前表单。 */
const issuedIdentity = ref('')
let emailCodeTimer: ReturnType<typeof setInterval> | undefined
let emailCodeCooldownDeadline = 0
let disposed = false
let captchaRequestGeneration = 0
let emailCodeRequestGeneration = 0
let resetRequestGeneration = 0

const form = reactive<PasswordResetFormData>({
  username: '',
  email: '',
  captchaId: '',
  captcha: '',
  emailCode: '',
  newPassword: '',
  confirmPassword: '',
})

const operationPending = computed(() => submitting.value || emailCodeSending.value)
const verificationRequestPending = computed(() => operationPending.value || captchaLoading.value)
const emailCodeCooldownSeconds = computed(() => (
  resolveEmailCodeCooldownSeconds(props.options.emailCodeCooldownSeconds)
))
const currentIdentity = computed(() => form.username + ' ' + form.email)

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 64, message: '用户名不能超过 64 位', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入注册邮箱', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(!value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
          ? undefined : new Error('邮箱格式不正确'))
      },
      trigger: 'blur',
    },
  ],
  captcha: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  emailCode: [
    {
      validator: (_rule, value, callback) => {
        if (!value) {
          callback(new Error('请输入邮箱验证码'))
          return
        }
        callback(issuedIdentity.value === currentIdentity.value
          ? undefined
          : new Error('请先向当前账号的邮箱发送验证码'))
      },
      trigger: 'blur',
    },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度为 8 至 64 位', trigger: 'blur' },
    {
      // 与后端 PasswordPolicy 保持同一条基础规则，弱口令的最终判定仍由服务端负责。
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '密码需同时包含字母与数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        callback(value === form.newPassword ? undefined : new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
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

async function refreshCaptcha() {
  if (disposed || captchaLoading.value) {
    return
  }
  captchaLoading.value = true
  const generation = ++captchaRequestGeneration
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
    if (!disposed && generation === captchaRequestGeneration) {
      clearCaptchaChallenge()
    }
  } finally {
    if (!disposed) {
      captchaLoading.value = false
    }
  }
}

/** 图形码是一次性挑战：无论发码成功还是失败，服务端那份都已消费，必须换新的。 */
function clearCaptchaChallenge() {
  form.captchaId = ''
  form.captcha = ''
  captchaImage.value = ''
  formRef.value?.clearValidate('captcha')
}

function clearIssuedEmailCode() {
  issuedIdentity.value = ''
  form.emailCode = ''
  formRef.value?.clearValidate('emailCode')
}

function clearEmailCodeTimer() {
  if (emailCodeTimer !== undefined) {
    clearInterval(emailCodeTimer)
    emailCodeTimer = undefined
  }
}

function startEmailCodeCountdown() {
  clearEmailCodeTimer()
  const seconds = emailCodeCooldownSeconds.value
  if (seconds <= 0) {
    emailCodeCountdown.value = 0
    return
  }
  emailCodeCooldownDeadline = Date.now() + seconds * 1000
  emailCodeCountdown.value = seconds
  emailCodeTimer = setInterval(() => {
    emailCodeCountdown.value = calculateCountdownSeconds(emailCodeCooldownDeadline, Date.now())
    if (emailCodeCountdown.value <= 0) {
      clearEmailCodeTimer()
    }
  }, 1000)
}

async function handleSendEmailCode() {
  if (disposed || verificationRequestPending.value || emailCodeCountdown.value > 0) {
    return
  }
  emailCodeSending.value = true
  const generation = ++emailCodeRequestGeneration
  try {
    const valid = await validateFields(PASSWORD_RESET_EMAIL_CODE_FIELDS)
    if (!valid || disposed || generation !== emailCodeRequestGeneration) {
      return
    }
    const payload = buildPasswordResetEmailCodePayload(form)
    const identity = currentIdentity.value
    const ttlSeconds = await sendPasswordResetEmailCode(payload)
    if (disposed || generation !== emailCodeRequestGeneration || identity !== currentIdentity.value) {
      return
    }
    issuedIdentity.value = identity
    form.emailCode = ''
    formRef.value?.clearValidate('emailCode')
    clearCaptchaChallenge()
    // 服务端对「账号不存在」与「账号存在」给出完全相同的响应，前端也必须照此措辞——
    // 说成「验证码已发送」等于把这个接口变成账号存在性探针的读数板
    const minutes = Math.max(1, Math.round(ttlSeconds / 60))
    ElMessage.success('若该用户名与邮箱对应一个可重置的账号，验证码已发出，' + minutes + ' 分钟内有效')
    startEmailCodeCountdown()
  } catch {
    if (!disposed) {
      clearIssuedEmailCode()
      clearCaptchaChallenge()
    }
  } finally {
    if (!disposed) {
      emailCodeSending.value = false
    }
  }
}

async function handleReset() {
  if (disposed || operationPending.value) {
    return
  }
  submitting.value = true
  const generation = ++resetRequestGeneration
  try {
    const valid = await validateFields(PASSWORD_RESET_SUBMIT_FIELDS)
    if (!valid || disposed || generation !== resetRequestGeneration) {
      return
    }
    const payload = buildPasswordResetPayload(form)
    const username = form.username
    await resetPassword(payload)
    if (disposed || generation !== resetRequestGeneration) {
      return
    }
    submittedUsername.value = username
    clearSensitiveData()
    state.value = 'success'
    clearEmailCodeTimer()
    emailCodeCountdown.value = 0
    emit('request-scroll-top')
  } catch (error) {
    // 服务端把「码错了」与「码已失效」合并成同一个响应（刻意的，见 PasswordResetService），
    // 前端无从分辨，因此保留输入让用户自己决定改一位还是重新获取——
    // 一律清空的话，输错一位就要再等一个冷却周期
    if (!disposed && !shouldKeepEmailCodeAfterPasswordResetFailure(error)) {
      clearIssuedEmailCode()
    }
  } finally {
    if (!disposed && generation === resetRequestGeneration) {
      submitting.value = false
    }
  }
}

/** 成功页只保留展示所需的用户名，立刻缩短新密码与一次性验证码在内存里的暴露窗口。 */
function clearSensitiveData() {
  form.emailCode = ''
  form.newPassword = ''
  form.confirmPassword = ''
  form.captcha = ''
  form.captchaId = ''
  issuedIdentity.value = ''
  captchaImage.value = ''
}

function backToLogin() {
  if (!operationPending.value) {
    clearSensitiveData()
    emit('back')
  }
}

function completeReset() {
  if (!operationPending.value) {
    emit('complete', submittedUsername.value)
  }
}

function handleEnter(event: KeyboardEvent) {
  const targetInsideButton = Boolean((event.target as HTMLElement | null)?.closest('button'))
  if (!shouldHandleRegisterEnter(event, targetInsideButton, operationPending.value)) {
    return
  }
  event.preventDefault()
  void handleReset()
}

onMounted(refreshCaptcha)
// 改了用户名或邮箱，手里那份码就不再对应当前表单；沿用注册面板的同一处理
watch(currentIdentity, () => {
  emailCodeRequestGeneration += 1
  clearIssuedEmailCode()
}, { flush: 'sync' })
onBeforeUnmount(() => {
  disposed = true
  captchaRequestGeneration += 1
  emailCodeRequestGeneration += 1
  resetRequestGeneration += 1
  clearEmailCodeTimer()
})
</script>

<template>
  <section
    class="forgot-panel"
    aria-labelledby="forgot-title"
    :style="{ '--login-primary-text': primaryTextColor }"
  >
    <template v-if="state === 'form'">
      <div class="panel-heading forgot-panel-heading">
        <div>
          <p class="eyebrow">找回密码</p>
          <h1 id="forgot-title">用注册邮箱重设密码</h1>
          <p>
            填写用户名与注册时登记的邮箱，验证码会发到该邮箱。重设成功后既有登录态全部失效，
            需要用新密码重新登录。
          </p>
        </div>
        <el-button class="back-login" link :disabled="operationPending" @click="backToLogin">返回登录</el-button>
      </div>

      <el-form
        ref="formRef"
        class="forgot-form"
        :model="form"
        :rules="rules"
        :disabled="operationPending"
        :aria-busy="operationPending"
        @keydown.enter="handleEnter"
        @submit.prevent
      >
        <label class="field-label" for="forgot-username">用户名 <span>必填</span></label>
        <el-form-item prop="username">
          <el-input id="forgot-username" v-model="form.username" size="large" autocomplete="username" placeholder="要找回密码的账号" :prefix-icon="'User'" />
        </el-form-item>

        <label class="field-label" for="forgot-email">注册邮箱 <span>必填</span></label>
        <el-form-item prop="email">
          <el-input id="forgot-email" v-model="form.email" size="large" autocomplete="email" placeholder="该账号登记的邮箱地址" :prefix-icon="'Message'" />
        </el-form-item>

        <label class="field-label" for="forgot-captcha">图形验证码 <span>发码时必填</span></label>
        <el-form-item prop="captcha">
          <div class="verification-row">
            <el-input id="forgot-captcha" v-model="form.captcha" size="large" maxlength="16" autocomplete="off" placeholder="请输入图中字符" :prefix-icon="'Key'" />
            <button v-if="captchaImage" type="button" class="captcha-challenge" aria-label="刷新图形验证码" :disabled="verificationRequestPending" @click="refreshCaptcha">
              <img :src="captchaImage" alt="图形验证码" />
              <span>换一张</span>
            </button>
            <el-button v-else class="captcha-fallback" size="large" :loading="captchaLoading" @click="refreshCaptcha">获取图形码</el-button>
          </div>
        </el-form-item>

        <label class="field-label" for="forgot-email-code">邮箱验证码 <span>必填</span></label>
        <el-form-item prop="emailCode">
          <div class="verification-row">
            <el-input id="forgot-email-code" v-model="form.emailCode" size="large" maxlength="16" inputmode="numeric" autocomplete="one-time-code" placeholder="请输入邮件中的验证码" :prefix-icon="'Message'" />
            <el-button class="email-code-button" size="large" :loading="emailCodeSending" :disabled="verificationRequestPending || emailCodeCountdown > 0" @click="handleSendEmailCode">
              {{ emailCodeCountdown > 0 ? emailCodeCountdown + ' 秒后重发' : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <label class="field-label" for="forgot-new-password">新密码 <span>必填</span></label>
        <el-form-item prop="newPassword">
          <el-input id="forgot-new-password" v-model="form.newPassword" type="password" size="large" show-password autocomplete="new-password" placeholder="至少 8 位，同时包含字母与数字" :prefix-icon="'Lock'" />
        </el-form-item>

        <label class="field-label" for="forgot-confirm-password">确认新密码 <span>必填</span></label>
        <el-form-item prop="confirmPassword">
          <el-input id="forgot-confirm-password" v-model="form.confirmPassword" type="password" size="large" show-password autocomplete="new-password" placeholder="再次输入新密码" :prefix-icon="'Lock'" />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="primary-action"
          :loading="submitting"
          :disabled="verificationRequestPending"
          @click="handleReset"
        >
          重设密码
        </el-button>

        <p class="privacy-note">
          出于账号安全，无论该用户名与邮箱是否对应一个账号，本页面都给出相同的提示。
          没收到邮件时请先确认两项填写无误，再检查垃圾邮件。
        </p>
      </el-form>
    </template>

    <div v-else class="reset-success" aria-live="polite">
      <div class="success-mark" aria-hidden="true"><el-icon><CircleCheckFilled /></el-icon></div>
      <p class="eyebrow">密码已重设</p>
      <h1 id="forgot-title">可以用新密码登录了，{{ submittedUsername }}</h1>
      <p>为保证安全，该账号此前的登录态已全部失效，其它设备需要重新登录。</p>
      <el-button
        type="primary"
        size="large"
        class="primary-action"
        :disabled="operationPending"
        @click="completeReset"
      >
        返回登录
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.forgot-panel,
.forgot-form {
  width: 100%;
}

.panel-heading {
  margin-bottom: 22px;
}

.forgot-panel-heading {
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
.reset-success > p:last-of-type {
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

.forgot-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.forgot-form :deep(.el-input__wrapper) {
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

.privacy-note {
  margin: 16px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.reset-success {
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

.reset-success .primary-action {
  margin-top: 28px;
}

@media (max-width: 480px) {
  .forgot-panel-heading {
    gap: 10px;
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
