import { describe, expect, it } from 'vitest'
import {
  buildEmailCodeRequestPayload,
  buildRegistrationPayload,
  calculateCountdownSeconds,
  createLoginSubmission,
  getEmailCodeRequestValidationFields,
  getLoginModePresentation,
  getRegisterStepPresentationFields,
  getRegisterVerificationPlan,
  getRegistrationStepValidationFields,
  isCaptchaConsumedByEmailCodeRequest,
  isCaptchaConsumedByRegistration,
  REGISTER_ACCOUNT_STEP,
  REGISTER_SECURITY_STEP,
  resolveEmailCodeCooldownSeconds,
  shouldHandleRegisterEnter,
  shouldClearEmailCodeAfterRegistrationFailure,
  shouldShowRegisterEntry,
  usesEmailCodeForRegistration,
  type RegisterField,
  type RegisterFormData,
  type RegisterVerificationOptions,
} from './loginPageModel'

describe('getLoginModePresentation', () => {
  it('本地账号使用本地身份文案', () => {
    expect(getLoginModePresentation('local')).toEqual({
      label: '本地账号',
      title: '欢迎回来',
      description: '使用本地账号进入智能体运营台。',
      usernameLabel: '用户名',
      usernamePlaceholder: '请输入用户名',
      passwordLabel: '密码',
      passwordPlaceholder: '请输入密码',
    })
  })

  it('OA 账号使用企业目录文案且不复用本地字段提示', () => {
    const local = getLoginModePresentation('local')
    const sso = getLoginModePresentation('sso')

    expect(sso).toEqual({
      label: 'OA 账号',
      title: 'OA 账号登录',
      description: '使用企业目录账号验证身份。',
      usernameLabel: 'OA 账号',
      usernamePlaceholder: '请输入 OA 账号',
      passwordLabel: 'OA 密码',
      passwordPlaceholder: '请输入 OA 密码',
    })
    expect(sso.usernameLabel).not.toBe(local.usernameLabel)
    expect(sso.passwordLabel).not.toBe(local.passwordLabel)
  })
})

describe('createLoginSubmission', () => {
  it('冻结登录模式与凭据快照，且请求凭据不混入 mode', () => {
    const form = { username: 'richard', password: 'Secret123', rememberMe: true }

    const submission = createLoginSubmission('local', form)
    form.username = 'changed'
    form.password = 'Changed456'
    form.rememberMe = false

    expect(submission).toEqual({
      mode: 'local',
      credentials: { username: 'richard', password: 'Secret123', rememberMe: true },
    })
    expect(submission.credentials).not.toHaveProperty('mode')
    expect(Object.isFrozen(submission)).toBe(true)
    expect(Object.isFrozen(submission.credentials)).toBe(true)
  })
})

describe('shouldShowRegisterEntry', () => {
  it.each([
    ['后端能力尚未加载', 'local', false, true],
    ['后端关闭自助注册', 'local', true, false],
    ['OA 模式', 'sso', true, true],
  ] as const)('%s时 fail-closed 隐藏注册入口', (_label, mode, optionsLoaded, selfServiceEnabled) => {
    expect(shouldShowRegisterEntry(mode, optionsLoaded, selfServiceEnabled)).toBe(false)
  })

  it('仅本地模式且后端明确开放时显示注册入口', () => {
    expect(shouldShowRegisterEntry('local', true, true)).toBe(true)
  })
})

describe('注册选项契约', () => {
  const form: RegisterFormData = {
    username: 'richard',
    nickname: 'Richard',
    email: 'richard@example.com',
    password: 'Secret123',
    confirmPassword: 'Secret123',
    captchaId: 'captcha-id',
    captcha: 'ABCD',
    emailCode: '246810',
  }
  const baseRegistrationPayload = {
    username: 'richard',
    nickname: 'Richard',
    email: 'richard@example.com',
    password: 'Secret123',
    confirmPassword: 'Secret123',
  }
  const cases: ReadonlyArray<{
    name: string
    options: RegisterVerificationOptions
    presentationFields: readonly RegisterField[]
    emailCodeRequestFields: readonly RegisterField[]
    registrationFields: readonly RegisterField[]
    emailCodePayload: Record<string, string>
    registrationPayload: Record<string, string>
    captchaConsumedByEmailCodeRequest: boolean
    captchaConsumedByRegistration: boolean
    emailCodeUsedForRegistration: boolean
  }> = [
    {
      name: '无需验证码',
      options: { captchaRequired: false, emailVerificationRequired: false },
      presentationFields: [],
      emailCodeRequestFields: [],
      registrationFields: [],
      emailCodePayload: { email: 'richard@example.com' },
      registrationPayload: baseRegistrationPayload,
      captchaConsumedByEmailCodeRequest: false,
      captchaConsumedByRegistration: false,
      emailCodeUsedForRegistration: false,
    },
    {
      name: '图形验证码保护最终注册',
      options: { captchaRequired: true, emailVerificationRequired: false },
      presentationFields: ['captcha'],
      emailCodeRequestFields: [],
      registrationFields: ['captcha'],
      emailCodePayload: { email: 'richard@example.com' },
      registrationPayload: {
        ...baseRegistrationPayload,
        captchaId: 'captcha-id',
        captcha: 'ABCD',
      },
      captchaConsumedByEmailCodeRequest: false,
      captchaConsumedByRegistration: true,
      emailCodeUsedForRegistration: false,
    },
    {
      name: '最终注册只校验邮箱验证码',
      options: { captchaRequired: false, emailVerificationRequired: true },
      presentationFields: ['emailCode'],
      emailCodeRequestFields: [],
      registrationFields: ['emailCode'],
      emailCodePayload: { email: 'richard@example.com' },
      registrationPayload: { ...baseRegistrationPayload, emailCode: '246810' },
      captchaConsumedByEmailCodeRequest: false,
      captchaConsumedByRegistration: false,
      emailCodeUsedForRegistration: true,
    },
    {
      name: '图形验证码只保护发码且最终只校验邮箱验证码',
      options: { captchaRequired: true, emailVerificationRequired: true },
      presentationFields: ['captcha', 'emailCode'],
      emailCodeRequestFields: ['captcha'],
      registrationFields: ['emailCode'],
      emailCodePayload: {
        email: 'richard@example.com',
        captchaId: 'captcha-id',
        captcha: 'ABCD',
      },
      registrationPayload: { ...baseRegistrationPayload, emailCode: '246810' },
      captchaConsumedByEmailCodeRequest: true,
      captchaConsumedByRegistration: false,
      emailCodeUsedForRegistration: true,
    },
  ]

  it.each(cases)('$name', ({
    options,
    presentationFields,
    emailCodeRequestFields,
    registrationFields,
    emailCodePayload,
    registrationPayload,
    captchaConsumedByEmailCodeRequest,
    captchaConsumedByRegistration,
    emailCodeUsedForRegistration,
  }) => {
    expect(getRegisterVerificationPlan(options)).toEqual({
      presentationFields,
      emailCodeRequestFields,
      registrationFields,
    })
    const plan = getRegisterVerificationPlan(options)
    const accountFields = getRegisterStepPresentationFields(REGISTER_ACCOUNT_STEP, plan)
    const securityPresentationFields = getRegisterStepPresentationFields(REGISTER_SECURITY_STEP, plan)
    const finalAccountFields = getRegistrationStepValidationFields(REGISTER_ACCOUNT_STEP, plan)
    const finalSecurityFields = getRegistrationStepValidationFields(REGISTER_SECURITY_STEP, plan)

    expect(accountFields).toEqual(['username', 'nickname', 'email'])
    expect(securityPresentationFields).toEqual(['password', 'confirmPassword', ...presentationFields])
    expect(getEmailCodeRequestValidationFields(plan)).toEqual(['email', ...emailCodeRequestFields])
    expect(finalAccountFields).toEqual(['username', 'nickname', 'email'])
    expect(finalSecurityFields).toEqual(['password', 'confirmPassword', ...registrationFields])
    expect(buildEmailCodeRequestPayload(form, plan)).toEqual(emailCodePayload)
    expect(buildRegistrationPayload(form, plan)).toEqual(registrationPayload)
    expect(isCaptchaConsumedByEmailCodeRequest(plan)).toBe(captchaConsumedByEmailCodeRequest)
    expect(isCaptchaConsumedByRegistration(plan)).toBe(captchaConsumedByRegistration)
    expect(usesEmailCodeForRegistration(plan)).toBe(emailCodeUsedForRegistration)
    expect(accountFields.filter((field) => securityPresentationFields.includes(field))).toEqual([])
  })

  it('空的选填昵称与邮箱归一化为 null，且不混入未启用的验证字段', () => {
    const plan = getRegisterVerificationPlan({
      captchaRequired: false,
      emailVerificationRequired: false,
    })

    expect(buildRegistrationPayload({
      ...form,
      nickname: '',
      email: '',
    }, plan)).toEqual({
      username: 'richard',
      nickname: null,
      email: null,
      password: 'Secret123',
      confirmPassword: 'Secret123',
    })
  })
})

describe('shouldClearEmailCodeAfterRegistrationFailure', () => {
  it.each([
    ['用户名或邮箱冲突发生在验证码核验之后', { code: 30004, message: '用户名已存在' }, true],
    ['普通邮箱码输错仍允许直接修正', { code: 30012, message: '邮箱验证码错误' }, false],
    ['新服务端用独立错误码要求重新获取', { code: 30013, message: '邮箱验证码已失效，请重新获取' }, true],
    ['旧服务端过期文案仍能要求重新获取', { code: 30012, message: '验证码已过期，请重新获取' }, true],
    ['旧服务端次数耗尽文案仍能要求重新获取', { code: 30012, message: '验证码错误次数过多，请重新获取' }, true],
    ['参数错误发生在验证码核验之前', { code: 30001, message: '参数校验失败' }, false],
    ['弱口令发生在验证码核验之前', { code: 30011, message: '密码强度不足' }, false],
    ['注册限流发生在验证码核验之前', { code: 40051, message: '注册请求过于频繁' }, false],
    ['非 2xx Axios 错误同样读取 response.data', {
      response: { data: { code: 30004, message: '该邮箱已注册' } },
    }, true],
    ['未知网络结果按可能已消费收敛', new Error('Network Error'), true],
  ] as const)('%s', (_label, error, expected) => {
    expect(shouldClearEmailCodeAfterRegistrationFailure(error)).toBe(expected)
  })
})

describe('shouldHandleRegisterEnter', () => {
  it('普通输入框 Enter 推进状态机', () => {
    expect(shouldHandleRegisterEnter(
      { isComposing: false, repeat: false, keyCode: 13 },
      false,
      false,
    )).toBe(true)
  })

  it.each([
    ['IME composing', { isComposing: true, repeat: false, keyCode: 13 }, false, false],
    ['IME legacy keyCode', { isComposing: false, repeat: false, keyCode: 229 }, false, false],
    ['长按重复', { isComposing: false, repeat: true, keyCode: 13 }, false, false],
    ['按钮自身', { isComposing: false, repeat: false, keyCode: 13 }, true, false],
    ['异步处理中', { isComposing: false, repeat: false, keyCode: 13 }, false, true],
  ] as const)('%s 不推进状态机', (_name, event, targetInsideButton, operationPending) => {
    expect(shouldHandleRegisterEnter(event, targetInsideButton, operationPending)).toBe(false)
  })
})

describe('calculateCountdownSeconds', () => {
  it('从截止时间重算，事件循环延迟不会累计漂移', () => {
    const deadline = 66_000

    expect(calculateCountdownSeconds(deadline, 1_000)).toBe(65)
    expect(calculateCountdownSeconds(deadline, 2_350)).toBe(64)
    expect(calculateCountdownSeconds(deadline, 66_000)).toBe(0)
    expect(calculateCountdownSeconds(deadline, 120_000)).toBe(0)
  })

  it.each([
    ['旧服务端缺省字段', undefined, 60],
    ['服务端自定义冷却', 37, 37],
    ['服务端关闭冷却', 0, 0],
    ['服务端非正值统一视为关闭', -1, 0],
  ] as const)('%s', (_name, configuredSeconds, expected) => {
    expect(resolveEmailCodeCooldownSeconds(configuredSeconds)).toBe(expected)
  })
})
