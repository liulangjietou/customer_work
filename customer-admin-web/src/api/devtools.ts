import { request } from './request'

/** 请求头/参数的键值对（允许同名重复，与后端 DTO 对齐） */
export interface HttpKeyValueItem {
  name: string
  value: string
}

export interface HttpSendRequest {
  method: string
  url: string
  headers: HttpKeyValueItem[]
  body?: string
}

export interface HttpSendResponse {
  statusCode: number | null
  headers: Record<string, string[]> | null
  body: string | null
  bodyBytes: number | null
  bodyTruncated: boolean
  durationMs: number
  redirectLocation: string | null
  error: string | null
}

/** 后端单次请求总时限 30s（含读取完整响应），前端在其上留出网络与序列化余量。 */
const HTTP_TOOL_TIMEOUT_MS = 45000

/** 开发者工具箱 · HTTP 请求工具：由后端代理发起真实请求（浏览器直连会被目标站 CORS 拦截）。 */
export function sendHttpRequest(data: HttpSendRequest) {
  return request<HttpSendResponse>({ url: '/devtools/http/send', method: 'post', data, timeout: HTTP_TOOL_TIMEOUT_MS })
}

// ---------- 证书解析 ----------

export interface CertInfo {
  /** 该证书自身的 PEM，供复制/下载；智能体侧不返回，页面侧必有 */
  pem?: string
  subject: string
  issuer: string
  serialNumberHex: string
  version: number
  notBeforeMs: number
  notAfterMs: number
  expired: boolean
  daysRemaining: number
  sigAlgName: string
  publicKeyAlgorithm: string
  publicKeyBits: number
  ca: boolean
  subjectAlternativeNames: string[]
  keyUsages: string[]
  sha1Fingerprint: string
  sha256Fingerprint: string
}

export interface PrivateKeyExportResponse {
  alias: string
  algorithm: string
  privateKeyPem: string
}

export interface CsrInfo {
  subject: string
  publicKeyAlgorithm: string
  publicKeyBits: number
  sigAlgName: string
  subjectAlternativeNames: string[]
}

export interface CertParseResponse {
  certificates: CertInfo[]
  csrs: CsrInfo[]
}

export interface CertMatchResponse {
  matched: boolean
  publicKeyAlgorithm: string
  reason: string
}

export interface KeystoreEntry {
  alias: string
  entryType: string
  chain: CertInfo[]
}

export interface KeystoreParseResponse {
  keystoreType: string
  entries: KeystoreEntry[]
}

/** 解析 PEM 文本中的证书/证书链/CSR（后端 Java 解析，内容不落库）。 */
export function parseCertPem(pemContent: string) {
  return request<CertParseResponse>({ url: '/devtools/cert/parse', method: 'post', data: { pemContent } })
}

/** 私钥与证书匹配校验（私钥仅在后端内存中参与一次签名-验签探测）。 */
export function matchCertKey(certPem: string, privateKeyPem: string) {
  return request<CertMatchResponse>({ url: '/devtools/cert/match', method: 'post', data: { certPem, privateKeyPem } })
}

/**
 * 导出密钥库指定条目的私钥 PEM。
 *
 * 与 parseKeystore 分开两次请求是刻意的：列举条目时私钥不该出现在响应体里，
 * 只有用户显式点"导出私钥"才把私钥取回来。
 */
export function exportKeystorePrivateKey(file: File, password: string, alias: string, keyPassword: string) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('password', password)
  formData.append('alias', alias)
  formData.append('keyPassword', keyPassword)
  return request<PrivateKeyExportResponse>({
    url: '/devtools/cert/keystore/private-key',
    method: 'post',
    data: formData,
  })
}

/** 解析 PFX/PKCS12 或 JKS 密钥库，列出条目与证书链。 */
export function parseKeystore(file: File, password: string) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('password', password)
  return request<KeystoreParseResponse>({ url: '/devtools/cert/keystore', method: 'post', data: formData })
}
