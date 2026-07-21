import CryptoJS from 'crypto-js'

export type AesMode = 'CBC' | 'ECB' | 'CTR'
export type AesPadding = 'Pkcs7' | 'NoPadding'
export type BinaryEncoding = 'utf8' | 'hex' | 'base64'
export type OutputFormat = 'hex' | 'base64'

const modeMap: Record<AesMode, unknown> = {
  CBC: CryptoJS.mode.CBC,
  ECB: CryptoJS.mode.ECB,
  CTR: CryptoJS.mode.CTR,
}
const paddingMap: Record<AesPadding, unknown> = {
  Pkcs7: CryptoJS.pad.Pkcs7,
  NoPadding: CryptoJS.pad.NoPadding,
}

const encodingLabel: Record<BinaryEncoding, string> = { utf8: 'UTF-8', hex: 'Hex', base64: 'Base64' }

/** 按选定编码把文本解析成 WordArray；编码本身不合法（如 Hex 里混了非十六进制字符）时给出具体原因。 */
export function decodeToWordArray(raw: string, encoding: BinaryEncoding): CryptoJS.lib.WordArray {
  try {
    if (encoding === 'utf8') return CryptoJS.enc.Utf8.parse(raw)
    if (encoding === 'hex') return CryptoJS.enc.Hex.parse(raw)
    return CryptoJS.enc.Base64.parse(raw)
  } catch {
    throw new Error(`不是合法的 ${encodingLabel[encoding]} 内容`)
  }
}

/** 校验密钥字节数是否为 AES 合法的 16/24/32（对应 AES-128/192/256），不合法返回具体提示文案。 */
export function validateKeyLength(wordArray: CryptoJS.lib.WordArray, encoding: BinaryEncoding): string | null {
  const bytes = wordArray.sigBytes
  if (bytes === 16 || bytes === 24 || bytes === 32) return null
  if (encoding === 'utf8') {
    return `密钥长度不合法：UTF-8 字节数需为 16/24/32，当前 ${bytes}`
  }
  return `密钥长度不合法：按 ${encodingLabel[encoding]} 解码后字节数需为 16/24/32，当前 ${bytes}`
}

/** 校验 IV 字节数必须为 16（AES 分组长度）；ECB 模式不使用 IV，不需要走这项校验。 */
export function validateIvLength(wordArray: CryptoJS.lib.WordArray, encoding: BinaryEncoding): string | null {
  const bytes = wordArray.sigBytes
  if (bytes === 16) return null
  return `IV 长度不合法：按 ${encodingLabel[encoding]} 解码后字节数需为 16，当前 ${bytes}`
}

export interface AesCipherParams {
  mode: AesMode
  padding: AesPadding
  key: CryptoJS.lib.WordArray
  iv?: CryptoJS.lib.WordArray
}

function buildCipherConfig(params: AesCipherParams): Record<string, unknown> {
  const cfg: Record<string, unknown> = { mode: modeMap[params.mode], padding: paddingMap[params.padding] }
  if (params.mode !== 'ECB' && params.iv) {
    cfg.iv = params.iv
  }
  return cfg
}

export function aesEncrypt(plainText: string, outputFormat: OutputFormat, params: AesCipherParams): string {
  const encrypted = CryptoJS.AES.encrypt(CryptoJS.enc.Utf8.parse(plainText), params.key, buildCipherConfig(params))
  return outputFormat === 'hex' ? encrypted.ciphertext.toString(CryptoJS.enc.Hex) : encrypted.ciphertext.toString(CryptoJS.enc.Base64)
}

export function aesDecrypt(cipherText: string, inputFormat: OutputFormat, params: AesCipherParams): string {
  let ciphertextWords: CryptoJS.lib.WordArray
  try {
    ciphertextWords = inputFormat === 'hex' ? CryptoJS.enc.Hex.parse(cipherText) : CryptoJS.enc.Base64.parse(cipherText)
  } catch {
    throw new Error(`密文不是合法的 ${inputFormat === 'hex' ? 'Hex' : 'Base64'} 内容`)
  }
  const cipherParams = CryptoJS.lib.CipherParams.create({ ciphertext: ciphertextWords })
  const decrypted = CryptoJS.AES.decrypt(cipherParams, params.key, buildCipherConfig(params))
  try {
    const text = decrypted.toString(CryptoJS.enc.Utf8)
    // crypto-js 遇到错误密钥/IV/模式时通常不主动抛错，只会产出乱码字节；乱码字节大概率不是合法 UTF-8，
    // 转 Utf8 字符串时 crypto-js 自身会抛 "Malformed UTF-8 data"，这里统一收敛成更明确的排查提示
    return text
  } catch {
    throw new Error('解密失败：结果不是合法的 UTF-8 文本，请检查密钥/IV/模式/填充方式是否正确')
  }
}
