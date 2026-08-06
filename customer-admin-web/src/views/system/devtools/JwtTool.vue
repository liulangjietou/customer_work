<script setup lang="ts">
// JWT 解析：解码与验签走后端（starter 的 JwtDevToolOps），与智能体侧 jwt_decode 同一实现。
// 令牌与密钥属敏感信息，与 AES 的密钥同等对待——用普通 ref，刷新即清空，不落 localStorage。
import { computed, ref } from 'vue'
import { decodeJwt, type JwtDecodeResponse } from '@/api/devtools'
import CopyButton from './CopyButton.vue'

const token = ref('')
const secret = ref('')
const secretEncoding = ref<'utf8' | 'hex' | 'base64'>('utf8')

const loading = ref(false)
const result = ref<JwtDecodeResponse | null>(null)

/** 签名状态映射成人话与配色，避免用户把"没校验"误读成"校验通过"。 */
const signatureHint = computed(() => {
  const status = result.value?.signatureStatus
  switch (status) {
    case 'VALID':
      return { type: 'success' as const, text: '签名校验通过' }
    case 'INVALID':
      return { type: 'error' as const, text: '签名校验不通过：密钥不匹配或令牌被篡改' }
    case 'UNSUPPORTED_ALG':
      return { type: 'warning' as const, text: `${result.value?.algorithm} 是非对称算法，本工具只能校验 HS256/HS384/HS512，此处仅解码未验签` }
    default:
      return { type: 'info' as const, text: '未校验签名（未填写密钥）' }
  }
})

const validityHint = computed(() => {
  const data = result.value
  if (!data) return null
  if (data.expired) {
    return { type: 'error' as const, text: `已过期（${data.expiresAt}）` }
  }
  if (data.notYetValid) {
    return { type: 'warning' as const, text: `尚未生效（${data.notBefore} 之后才可用）` }
  }
  if (data.expiresAt) {
    return { type: 'success' as const, text: `有效，${data.expiresAt} 过期（剩余 ${formatRemaining(data.secondsRemaining)}）` }
  }
  return { type: 'info' as const, text: '令牌未声明 exp，不会自行过期' }
})

function formatRemaining(seconds: number | null): string {
  if (seconds === null) return '-'
  const abs = Math.abs(seconds)
  const days = Math.floor(abs / 86400)
  const hours = Math.floor((abs % 86400) / 3600)
  const minutes = Math.floor((abs % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分`
  return `${minutes} 分 ${abs % 60} 秒`
}

async function handleDecode() {
  if (!token.value.trim()) {
    ElMessage.warning('请先粘贴 JWT')
    return
  }
  loading.value = true
  try {
    result.value = await decodeJwt(token.value.trim(), secret.value || undefined, secretEncoding.value)
  } finally {
    loading.value = false
  }
}

function handleClear() {
  token.value = ''
  secret.value = ''
  result.value = null
}
</script>

<template>
  <div class="jwt-tool">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="令牌与密钥只在本次请求中解析，不落库、不写日志；页面刷新即清空，不会保存到本地。"
      class="privacy-tip"
    />

    <div class="pane">
      <div class="pane-header"><span>JWT</span></div>
      <textarea v-model="token" class="code-textarea token-input" spellcheck="false" placeholder="粘贴 JWT（header.payload.signature）…" />
    </div>

    <el-form label-width="100px" class="param-form">
      <el-form-item label="签名密钥">
        <div class="key-row">
          <el-input v-model="secret" placeholder="可选，仅 HS256/HS384/HS512 可校验签名" clearable style="flex: 1" />
          <el-select v-model="secretEncoding" style="width: 110px">
            <el-option label="UTF-8" value="utf8" />
            <el-option label="Hex" value="hex" />
            <el-option label="Base64" value="base64" />
          </el-select>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleDecode">解析</el-button>
        <el-button @click="handleClear">清空</el-button>
      </el-form-item>
    </el-form>

    <template v-if="result">
      <el-alert v-if="result.unsigned" type="error" :closable="false" show-icon class="status-alert"
        title="该令牌 alg=none，没有签名保护，内容可被任意伪造，绝不能当作可信凭据" />
      <el-alert v-if="validityHint" :type="validityHint.type" :closable="false" show-icon class="status-alert" :title="validityHint.text" />
      <el-alert :type="signatureHint.type" :closable="false" show-icon class="status-alert" :title="signatureHint.text" />

      <el-descriptions :column="3" border size="small" class="claims">
        <el-descriptions-item label="算法">{{ result.algorithm ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ result.type ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="令牌 ID (jti)">{{ result.jwtId ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="签发方 (iss)">{{ result.issuer ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="主体 (sub)">{{ result.subject ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="受众 (aud)">{{ result.audience ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="签发时间 (iat)">{{ result.issuedAt ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="生效时间 (nbf)">{{ result.notBefore ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="过期时间 (exp)">{{ result.expiresAt ?? '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="panes">
        <div class="pane">
          <div class="pane-header">
            <span>Header</span>
            <CopyButton :text="result.header" label="Header" />
          </div>
          <textarea class="code-textarea" readonly spellcheck="false" :value="result.header" />
        </div>
        <div class="pane">
          <div class="pane-header">
            <span>Payload</span>
            <CopyButton :text="result.payload" label="Payload" />
          </div>
          <textarea class="code-textarea" readonly spellcheck="false" :value="result.payload" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.jwt-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.privacy-tip {
  margin-bottom: 4px;
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

.status-alert {
  margin-bottom: 4px;
}

.claims {
  margin: 4px 0 8px;
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
  min-height: 200px;
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

.token-input {
  min-height: 110px;
  word-break: break-all;
}

.code-textarea:focus {
  border-color: var(--theme-primary, var(--el-color-primary));
  background: var(--el-bg-color);
}

.code-textarea[readonly] {
  background: var(--el-fill-color-light);
}
</style>
