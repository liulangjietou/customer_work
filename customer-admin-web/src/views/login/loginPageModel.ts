import type { EmailCodeRequest, RegisterRequest } from '@/types/api'

export type LoginMode = 'local' | 'sso'

export interface LoginModePresentation {
  label: string
  title: string
  description: string
  usernameLabel: string
  usernamePlaceholder: string
  passwordLabel: string
  passwordPlaceholder: string
}

const LOGIN_MODE_PRESENTATIONS: Readonly<Record<LoginMode, Readonly<LoginModePresentation>>> = {
  local: {
    label: '本地账号',
    title: '欢迎回来',
    description: '使用本地账号进入智能体运营台。',
    usernameLabel: '用户名',
    usernamePlaceholder: '请输入用户名',
    passwordLabel: '密码',
    passwordPlaceholder: '请输入密码',
  },
  sso: {
    label: 'OA 账号',
    title: 'OA 账号登录',
    description: '使用企业目录账号验证身份。',
    usernameLabel: 'OA 账号',
    usernamePlaceholder: '请输入 OA 账号',
    passwordLabel: 'OA 密码',
    passwordPlaceholder: '请输入 OA 密码',
  },
}

export function getLoginModePresentation(mode: LoginMode): Readonly<LoginModePresentation> {
  return LOGIN_MODE_PRESENTATIONS[mode]
}

export interface LoginFormData {
  username: string
  password: string
  rememberMe: boolean
}

export interface LoginSubmissionCredentials extends LoginFormData {
  captchaProof: string
}

export interface LoginSubmission {
  readonly mode: LoginMode
  readonly credentials: Readonly<LoginSubmissionCredentials>
}

/**
 * 登录请求发出前冻结身份模式与凭据快照，避免 await 期间的表单变化污染后续记忆账号和登录态处理。
 */
export function createLoginSubmission(
  mode: LoginMode,
  form: Readonly<LoginFormData>,
  captchaProof: string,
): Readonly<LoginSubmission> {
  const credentials = Object.freeze({
    username: form.username,
    password: form.password,
    rememberMe: form.rememberMe,
    captchaProof,
  })
  return Object.freeze({ mode, credentials })
}

/**
 * 注册入口必须以后端能力接口成功返回为前提，接口未完成或失败时按关闭处理。
 */
export function shouldShowRegisterEntry(
  mode: LoginMode,
  optionsLoaded: boolean,
  selfServiceEnabled: boolean,
): boolean {
  return mode === 'local' && optionsLoaded && selfServiceEnabled
}

export type RegisterVerificationField = 'captcha' | 'emailCode'

export interface RegisterVerificationOptions {
  captchaRequired: boolean
}

/**
 * 图形验证码和邮箱验证码分别保护不同动作，不能只按“是否显示”合并处理。
 */
export interface RegisterVerificationPlan {
  /** 安全验证步骤需要展示的字段。 */
  presentationFields: readonly RegisterVerificationField[]
  /** 发送邮箱验证码前需要校验的字段。 */
  emailCodeRequestFields: readonly RegisterVerificationField[]
  /** 最终提交注册时需要校验的字段。 */
  registrationFields: readonly RegisterVerificationField[]
}

const CAPTCHA_FIELD: RegisterVerificationField = 'captcha'
const EMAIL_CODE_FIELD: RegisterVerificationField = 'emailCode'

/**
 * 邮箱验证码是注册的固定要求，注册提交只认它；随部署形态变化的只有
 * 「发码那一步要不要图形码」这一件事。
 */
export function getRegisterVerificationPlan(
  options: RegisterVerificationOptions,
): RegisterVerificationPlan {
  return {
    presentationFields: options.captchaRequired
      ? [CAPTCHA_FIELD, EMAIL_CODE_FIELD]
      : [EMAIL_CODE_FIELD],
    emailCodeRequestFields: options.captchaRequired ? [CAPTCHA_FIELD] : [],
    registrationFields: [EMAIL_CODE_FIELD],
  }
}

export const REGISTER_ACCOUNT_STEP = 1 as const
export const REGISTER_SECURITY_STEP = 2 as const
export type RegisterStep = typeof REGISTER_ACCOUNT_STEP | typeof REGISTER_SECURITY_STEP

export type RegisterField =
  | 'username'
  | 'nickname'
  | 'email'
  | 'password'
  | 'confirmPassword'
  | RegisterVerificationField

const REGISTER_ACCOUNT_FIELDS: readonly RegisterField[] = ['username', 'nickname', 'email']
const REGISTER_SECURITY_FIELDS: readonly RegisterField[] = ['password', 'confirmPassword']

/**
 * 页面分步仅负责决定展示哪些控件，不能被最终注册校验直接复用。
 * 邮箱验证模式下，图形码在发码后已消费，但仍需保留控件供重发。
 */
export function getRegisterStepPresentationFields(
  step: RegisterStep,
  plan: RegisterVerificationPlan,
): readonly RegisterField[] {
  if (step === REGISTER_ACCOUNT_STEP) {
    return REGISTER_ACCOUNT_FIELDS
  }

  return [
    ...REGISTER_SECURITY_FIELDS,
    ...plan.presentationFields,
  ]
}

/** 发送邮箱验证码时的完整校验集合。 */
export function getEmailCodeRequestValidationFields(
  plan: RegisterVerificationPlan,
): readonly RegisterField[] {
  return ['email', ...plan.emailCodeRequestFields]
}

/** 发码请求是否会消费图形码；无论成功失败，旧挑战都不能再次使用。 */
export function isCaptchaConsumedByEmailCodeRequest(
  plan: RegisterVerificationPlan,
): boolean {
  return plan.emailCodeRequestFields.includes(CAPTCHA_FIELD)
}

/**
 * 最终注册按步骤校验：只要求邮箱验证码，不再校验发码那一步已消费的图形码。
 */
export function getRegistrationStepValidationFields(
  step: RegisterStep,
  plan: RegisterVerificationPlan,
): readonly RegisterField[] {
  if (step === REGISTER_ACCOUNT_STEP) {
    return REGISTER_ACCOUNT_FIELDS
  }

  return [
    ...REGISTER_SECURITY_FIELDS,
    ...plan.registrationFields,
  ]
}

const PARAM_INVALID_CODE = 30001
const PARAM_MISSING_CODE = 30002
const RESOURCE_DUPLICATE_CODE = 30004
const PASSWORD_TOO_WEAK_CODE = 30011
const EMAIL_CODE_INVALID_CODE = 30012
const EMAIL_CODE_REISSUE_REQUIRED_CODE = 30013
const FEATURE_NOT_AVAILABLE_CODE = 40050
const REGISTER_TOO_FREQUENT_CODE = 40051
const EMAIL_CODE_REISSUE_HINT = '请重新获取'

interface RegistrationFailureBody {
  code?: unknown
  message?: unknown
  response?: {
    data?: unknown
  }
}

function registrationFailureBody(error: unknown): RegistrationFailureBody | undefined {
  if (!error || typeof error !== 'object') {
    return undefined
  }
  const candidate = error as RegistrationFailureBody
  const responseData = candidate.response?.data
  return responseData && typeof responseData === 'object'
    ? responseData as RegistrationFailureBody
    : candidate
}

/**
 * 注册失败不等于邮箱码已消费：表单、弱口令与限流发生在邮箱码核验之前，普通输错也允许继续尝试。
 * 唯一键冲突发生在核验通过之后；未知的网络/服务端结果无法证明凭据仍有效，按已消费收敛。
 */
export function shouldClearEmailCodeAfterRegistrationFailure(error: unknown): boolean {
  const body = registrationFailureBody(error)
  const code = typeof body?.code === 'number' ? body.code : undefined
  if (code === RESOURCE_DUPLICATE_CODE) {
    return true
  }
  if (code === EMAIL_CODE_REISSUE_REQUIRED_CODE) {
    return true
  }
  if (code === EMAIL_CODE_INVALID_CODE) {
    // 兼容尚未升级独立 30013 错误码的旧服务端；新服务端不再依赖中文文案判定。
    return typeof body?.message === 'string' && body.message.includes(EMAIL_CODE_REISSUE_HINT)
  }
  if ([
    PARAM_INVALID_CODE,
    PARAM_MISSING_CODE,
    PASSWORD_TOO_WEAK_CODE,
    FEATURE_NOT_AVAILABLE_CODE,
    REGISTER_TOO_FREQUENT_CODE,
  ].includes(code ?? Number.NaN)) {
    return false
  }
  return true
}

export interface RegisterFormData {
  username: string
  nickname: string
  email: string
  password: string
  confirmPassword: string
  captchaId: string
  captcha: string
  emailCode: string
}

/** 生产发码请求与契约测试共用同一个载荷构造入口。 */
export function buildEmailCodeRequestPayload(
  form: Readonly<RegisterFormData>,
  plan: RegisterVerificationPlan,
): EmailCodeRequest {
  if (plan.emailCodeRequestFields.includes(CAPTCHA_FIELD)) {
    return {
      email: form.email,
      captchaId: form.captchaId,
      captcha: form.captcha,
    }
  }

  return { email: form.email }
}

/** 生产注册请求与契约测试共用同一个载荷构造入口。 */
export function buildRegistrationPayload(
  form: Readonly<RegisterFormData>,
  plan: RegisterVerificationPlan,
): RegisterRequest {
  const payload: RegisterRequest = {
    username: form.username,
    nickname: form.nickname || null,
    email: form.email,
    password: form.password,
    confirmPassword: form.confirmPassword,
  }

  if (plan.registrationFields.includes(EMAIL_CODE_FIELD)) {
    payload.emailCode = form.emailCode
  }

  return payload
}

export interface RegisterEnterEventState {
  isComposing: boolean
  repeat: boolean
  keyCode: number
}

/** 输入法合成、长按重复和按钮自身的 Enter 都不能推进注册状态机。 */
export function shouldHandleRegisterEnter(
  event: Readonly<RegisterEnterEventState>,
  targetInsideButton: boolean,
  operationPending: boolean,
): boolean {
  return !event.isComposing
    && event.keyCode !== 229
    && !event.repeat
    && !targetInsideButton
    && !operationPending
}

/** 倒计时始终从截止时间重算，后台标签页节流后也不会累计漂移。 */
export function calculateCountdownSeconds(deadline: number, now: number): number {
  return Math.max(0, Math.ceil((deadline - now) / 1000))
}

export const DEFAULT_EMAIL_CODE_COOLDOWN_SECONDS = 60

/**
 * 旧服务端未返回该字段时保持 60 秒兼容值；0 与负数按服务端语义表示关闭冷却。
 */
export function resolveEmailCodeCooldownSeconds(configuredSeconds?: number): number {
  return Math.max(0, configuredSeconds ?? DEFAULT_EMAIL_CODE_COOLDOWN_SECONDS)
}
