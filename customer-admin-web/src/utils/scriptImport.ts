/**
 * ScriptCat/Tampermonkey 登录脚本解析器（启发式，尽力而为）。
 *
 * 把一段 userscript 文本解析成"新增站点"表单可预填的字段。设计原则：
 * - 地址/名称几乎必中；账号/密码/选择器尽力提取，取不到就留空，交由用户核对补齐。
 * - 不追求覆盖所有 JS 变体（那等于写语义分析器）；只覆盖常见硬编码登录脚本的固定模式。
 * 纯函数、无框架依赖，便于独立测试。
 */

export interface ParsedScript {
  name?: string
  url?: string
  account?: string
  password?: string
  usernameSelector?: string
  passwordSelector?: string
  submitSelector?: string
  fillMode: 'auto' | 'typing'
  submitMode: 'click' | 'formSubmit'
  /** 命中的可预填字段数（name/url/account/password/三选择器），用于给用户反馈。 */
  matchedCount: number
  /** 需要人工确认的提示（如地址含通配符）。 */
  warnings: string[]
}

/** 取 UserScript 头里某指令的所有值。 */
function metaValues(text: string, key: string): string[] {
  const re = new RegExp(`//\\s*@${key}\\s+(\\S.*?)\\s*$`, 'gm')
  const out: string[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    out.push(m[1].trim())
  }
  return out
}

/** 从 @match/@include 规范出一个可用的站点地址。 */
function parseUrl(text: string, warnings: string[]): string | undefined {
  const raw = [...metaValues(text, 'match'), ...metaValues(text, 'include')][0]
  if (!raw) {
    return undefined
  }
  // 取 scheme://host[:port]（到路径前的第一个 /），再去掉尾部通配 *
  const m = raw.match(/(https?:\/\/[^\s/]+)/)
  if (!m) {
    warnings.push('无法从 @match 解析出地址，请手动填写')
    return undefined
  }
  const url = m[1].replace(/\*+$/, '')
  if (url.includes('*')) {
    warnings.push(`地址含通配符（${url}），请改成具体地址`)
  }
  return url
}

/** 账号/密码：先按关键词精确归类，再用 setElementValue/setNativeValue 的顺序兜底。 */
function parseCredentials(body: string): { account?: string; password?: string } {
  const userKey = /(user(?:name)?|account|ldap_username|txt_email|email)/i
  const passKey = /(password|passwd|pwd|ldap_password|txt_pwd)/i

  let account: string | undefined
  let password: string | undefined

  // 规则1：const/let/var NAME = 'x'  且  规则2：对象属性 name: 'x'
  const assignRe = /(?:const|let|var\s+)?([A-Za-z_$][\w$]*)\s*[:=]\s*(['"])([^'"]*)\2/g
  let a: RegExpExecArray | null
  while ((a = assignRe.exec(body)) !== null) {
    const [, ident, , value] = a
    if (!value) {
      continue
    }
    if (!account && userKey.test(ident) && !passKey.test(ident)) {
      account = value
    } else if (!password && passKey.test(ident)) {
      password = value
    }
  }

  // 规则3：xxx.value = 'y'（变量名带关键词）
  const valueRe = /([A-Za-z_$][\w$]*)\.value\s*=\s*(['"])([^'"]*)\2/g
  let v: RegExpExecArray | null
  while ((v = valueRe.exec(body)) !== null) {
    const [, ident, , value] = v
    if (!value) {
      continue
    }
    if (!account && userKey.test(ident) && !passKey.test(ident)) {
      account = value
    } else if (!password && passKey.test(ident)) {
      password = value
    }
  }

  // 规则4：setElementValue/setNativeValue(target, 'value')——先按 target 关键词，再按顺序兜底
  const anon: string[] = []
  const setRe = /set(?:Element|Native|React)Value\s*\(\s*([^,]+?)\s*,\s*(['"])([^'"]*)\2/g
  let s: RegExpExecArray | null
  while ((s = setRe.exec(body)) !== null) {
    const [, target, , value] = s
    if (!value) {
      continue
    }
    if (passKey.test(target)) {
      password ??= value
    } else if (userKey.test(target)) {
      account ??= value
    } else {
      anon.push(value)
    }
  }
  // 顺序兜底：匿名 setValue 按"先账号后密码"的通用书写顺序补齐仍缺的字段
  if (anon.length > 0) {
    if (!account) {
      account = anon.shift()
    }
    if (!password && anon.length > 0) {
      password = anon.shift()
    }
  }

  return { account, password }
}

interface Selectors {
  usernameSelector?: string
  passwordSelector?: string
  submitSelector?: string
}

/** 归类一个 CSS 选择器到 用户名/密码/按钮。 */
function classifySelector(sel: string, out: Selectors): void {
  const lower = sel.toLowerCase()
  if (!out.passwordSelector && (lower.includes('password') || lower.includes('pwd'))) {
    out.passwordSelector = sel
    return
  }
  // 按钮信号用强特征（submit/btn/button/primary），刻意不含 "login"——
  // 它作为弱子串会误伤 customeLogin/loginForm 这类用户名框的类名
  if (
    !out.submitSelector &&
    (lower.includes('submit') || lower.includes('btn') || lower.includes('button') ||
      lower.includes('primary') || lower.includes('登录'))
  ) {
    out.submitSelector = sel
    return
  }
  if (
    !out.usernameSelector &&
    (lower.includes('user') || lower.includes('email') || lower.includes('text') ||
      lower.includes('用户名') || lower.includes('account'))
  ) {
    out.usernameSelector = sel
  }
}

/** 从 querySelector / getElementsByName / getElementsByClassName 提取三类选择器。 */
function parseSelectors(body: string): Selectors {
  const out: Selectors = {}

  // querySelector('SEL')——用非贪婪匹配到同种闭合引号，兼容选择器内部的另一种引号
  // （如 input[placeholder="Username"]，外层单引号内含双引号）
  const qsRe = /querySelector\(\s*(['"])(.*?)\1\s*\)/g
  let q: RegExpExecArray | null
  while ((q = qsRe.exec(body)) !== null) {
    classifySelector(q[2], out)
  }

  // getElementsByName('x') -> [name="x"]
  const nameRe = /getElementsByName\(\s*(['"])(.*?)\1\s*\)/g
  let n: RegExpExecArray | null
  while ((n = nameRe.exec(body)) !== null) {
    classifySelector(`[name="${n[2]}"]`, out)
  }

  // getElementsByClassName('x') -> .x（位置法信息丢失，归为用户名候选，密码留空走脚本启发式）
  const clsRe = /getElementsByClassName\(\s*(['"])(.*?)\1\s*\)/g
  let c: RegExpExecArray | null
  while ((c = clsRe.exec(body)) !== null) {
    classifySelector(`.${c[2]}`, out)
  }

  return out
}

function parseFillMode(body: string): 'auto' | 'typing' {
  // 逐字打字特征：insertText 或 InputEvent 携带单字符 data
  if (/insertText/.test(body) || /InputEvent\([^)]*data\s*:/.test(body)) {
    return 'typing'
  }
  return 'auto'
}

function parseSubmitMode(body: string): 'click' | 'formSubmit' {
  // 有 form.submit()（非 requestSubmit、非按钮 .click）视为表单提交
  if (/\.submit\s*\(\s*\)/.test(body) && !/requestSubmit/.test(body)) {
    return 'formSubmit'
  }
  return 'click'
}

/** 解析 userscript 文本，返回可预填字段。 */
export function parseUserscript(text: string): ParsedScript {
  const warnings: string[] = []

  const name = metaValues(text, 'name')[0]
  const url = parseUrl(text, warnings)
  const { account, password } = parseCredentials(text)
  const selectors = parseSelectors(text)
  const fillMode = parseFillMode(text)
  const submitMode = parseSubmitMode(text)

  const fields = [name, url, account, password,
    selectors.usernameSelector, selectors.passwordSelector, selectors.submitSelector]
  const matchedCount = fields.filter(Boolean).length

  return {
    name,
    url,
    account,
    password,
    usernameSelector: selectors.usernameSelector,
    passwordSelector: selectors.passwordSelector,
    submitSelector: selectors.submitSelector,
    fillMode,
    submitMode,
    matchedCount,
    warnings,
  }
}
