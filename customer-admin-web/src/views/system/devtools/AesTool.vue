<script setup lang="ts">
// AES 加解密：参数较多、误触发代价高（密钥/IV 没配对时结果就是乱码），按验收要求用显式"加密/解密"
// 按钮，不接自动 debounce 计算。密钥与 IV 是敏感输入，明确不接入 usePersistedRef（不落 localStorage）。
import { computed, ref, watch } from 'vue'
import type { AesMode, AesPadding, BinaryEncoding, OutputFormat } from './composables/aesCrypto'
import { aesDecrypt, aesEncrypt, decodeToWordArray, validateIvLength, validateKeyLength } from './composables/aesCrypto'
import { usePersistedRef } from './composables/useToolStorage'
import CopyButton from './CopyButton.vue'

const mode = usePersistedRef<AesMode>('aes:mode', 'CBC')
const padding = usePersistedRef<AesPadding>('aes:padding', 'Pkcs7')
const outputFormat = usePersistedRef<OutputFormat>('aes:outputFormat', 'hex')
const activeTab = usePersistedRef<'encrypt' | 'decrypt'>('aes:activeTab', 'encrypt')

const keyEncoding = usePersistedRef<BinaryEncoding>('aes:keyEncoding', 'utf8')
const ivEncoding = usePersistedRef<BinaryEncoding>('aes:ivEncoding', 'utf8')
// 密钥/IV 属于敏感输入，用普通 ref，刷新页面即清空，不落 localStorage
const keyText = ref('')
const ivText = ref('')

const encryptInput = usePersistedRef('aes:encryptInput', '')
const decryptInput = usePersistedRef('aes:decryptInput', '')
const encryptOutput = ref('')
const decryptOutput = ref('')
const encryptError = ref('')
const decryptError = ref('')

const needsIv = computed(() => mode.value !== 'ECB')

// CTR 是流密码模式，块填充语义在此不适用；切到 CTR 时强制无填充并禁用选择器，避免误配置
watch(mode, (m) => {
  if (m === 'CTR') {
    padding.value = 'NoPadding'
  }
})

const keyByteLengthHint = computed(() => {
  try {
    return decodeToWordArray(keyText.value, keyEncoding.value).sigBytes
  } catch {
    return null
  }
})

/** 校验并解码密钥/IV，任一失败时返回错误文案（null 表示全部通过）。 */
function validateAndBuildParams(): { key: CryptoJS.lib.WordArray; iv?: CryptoJS.lib.WordArray } | string {
  if (!keyText.value) {
    return '请输入密钥'
  }
  let keyWords: CryptoJS.lib.WordArray
  try {
    keyWords = decodeToWordArray(keyText.value, keyEncoding.value)
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
  const keyErr = validateKeyLength(keyWords, keyEncoding.value)
  if (keyErr) return keyErr

  if (!needsIv.value) {
    return { key: keyWords }
  }
  if (!ivText.value) {
    return '请输入 IV'
  }
  let ivWords: CryptoJS.lib.WordArray
  try {
    ivWords = decodeToWordArray(ivText.value, ivEncoding.value)
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
  const ivErr = validateIvLength(ivWords, ivEncoding.value)
  if (ivErr) return ivErr

  return { key: keyWords, iv: ivWords }
}

function handleEncrypt() {
  encryptError.value = ''
  encryptOutput.value = ''
  if (!encryptInput.value) {
    encryptError.value = '请输入待加密的明文'
    return
  }
  const params = validateAndBuildParams()
  if (typeof params === 'string') {
    encryptError.value = params
    return
  }
  try {
    encryptOutput.value = aesEncrypt(encryptInput.value, outputFormat.value, {
      mode: mode.value,
      padding: padding.value,
      key: params.key,
      iv: params.iv,
    })
  } catch (e) {
    encryptError.value = e instanceof Error ? e.message : String(e)
  }
}

function handleDecrypt() {
  decryptError.value = ''
  decryptOutput.value = ''
  if (!decryptInput.value) {
    decryptError.value = '请输入待解密的密文'
    return
  }
  const params = validateAndBuildParams()
  if (typeof params === 'string') {
    decryptError.value = params
    return
  }
  try {
    decryptOutput.value = aesDecrypt(decryptInput.value, outputFormat.value, {
      mode: mode.value,
      padding: padding.value,
      key: params.key,
      iv: params.iv,
    })
  } catch (e) {
    decryptError.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="aes-tool">
    <el-form label-width="100px" class="param-form">
      <el-form-item label="模式">
        <el-radio-group v-model="mode">
          <el-radio-button value="CBC">CBC</el-radio-button>
          <el-radio-button value="ECB">ECB</el-radio-button>
          <el-radio-button value="CTR">CTR</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="填充">
        <el-radio-group v-model="padding" :disabled="mode === 'CTR'">
          <el-radio-button value="Pkcs7">PKCS7</el-radio-button>
          <el-radio-button value="NoPadding">NoPadding</el-radio-button>
        </el-radio-group>
        <span v-if="mode === 'CTR'" class="form-tip">CTR 是流密码模式，不使用块填充，固定 NoPadding</span>
      </el-form-item>

      <el-form-item label="输出格式">
        <el-radio-group v-model="outputFormat">
          <el-radio-button value="hex">Hex</el-radio-button>
          <el-radio-button value="base64">Base64</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="密钥">
        <div class="key-row">
          <el-input v-model="keyText" placeholder="输入密钥" clearable style="flex: 1" />
          <el-select v-model="keyEncoding" style="width: 110px">
            <el-option label="UTF-8" value="utf8" />
            <el-option label="Hex" value="hex" />
            <el-option label="Base64" value="base64" />
          </el-select>
        </div>
        <span v-if="keyText && keyByteLengthHint !== null" class="form-tip">解码后 {{ keyByteLengthHint }} 字节</span>
      </el-form-item>

      <el-form-item label="IV">
        <div class="key-row">
          <el-input v-model="ivText" :disabled="!needsIv" placeholder="输入 IV（16 字节）" clearable style="flex: 1" />
          <el-select v-model="ivEncoding" :disabled="!needsIv" style="width: 110px">
            <el-option label="UTF-8" value="utf8" />
            <el-option label="Hex" value="hex" />
            <el-option label="Base64" value="base64" />
          </el-select>
        </div>
        <span v-if="!needsIv" class="form-tip">ECB 模式不使用 IV</span>
      </el-form-item>
    </el-form>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="加密" name="encrypt">
        <div class="panes">
          <div class="pane">
            <div class="pane-header"><span>明文</span></div>
            <textarea v-model="encryptInput" class="code-textarea" spellcheck="false" placeholder="输入待加密的明文…" />
          </div>
          <div class="pane">
            <div class="pane-header">
              <span>密文（{{ outputFormat === 'hex' ? 'Hex' : 'Base64' }}）</span>
              <CopyButton :text="encryptOutput" label="密文" />
            </div>
            <textarea class="code-textarea" readonly spellcheck="false" :value="encryptOutput" />
          </div>
        </div>
        <div v-if="encryptError" class="field-error">{{ encryptError }}</div>
        <el-button type="primary" class="action-button" @click="handleEncrypt">加密</el-button>
      </el-tab-pane>

      <el-tab-pane label="解密" name="decrypt">
        <div class="panes">
          <div class="pane">
            <div class="pane-header"><span>密文（{{ outputFormat === 'hex' ? 'Hex' : 'Base64' }}）</span></div>
            <textarea v-model="decryptInput" class="code-textarea" spellcheck="false" placeholder="输入待解密的密文…" />
          </div>
          <div class="pane">
            <div class="pane-header">
              <span>明文</span>
              <CopyButton :text="decryptOutput" label="明文" />
            </div>
            <textarea class="code-textarea" readonly spellcheck="false" :value="decryptOutput" />
          </div>
        </div>
        <div v-if="decryptError" class="field-error">{{ decryptError }}</div>
        <el-button type="primary" class="action-button" @click="handleDecrypt">解密</el-button>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.aes-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.param-form {
  max-width: 720px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.panes {
  display: flex;
  gap: 16px;
}

.pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
  min-height: 24px;
}

.code-textarea {
  min-height: 220px;
  width: 100%;
  box-sizing: border-box;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  resize: vertical;
  outline: none;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-primary);
}

.code-textarea:focus {
  border-color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-bg-color);
}

.code-textarea[readonly] {
  background: var(--el-fill-color-light);
}

.field-error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
}

.action-button {
  margin-top: 12px;
}
</style>
