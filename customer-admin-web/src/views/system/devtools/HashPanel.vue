<script setup lang="ts">
import { ref } from 'vue'
import CryptoJS from 'crypto-js'
import { usePersistedRef } from './composables/useToolStorage'
import { useDebouncedEffect } from './composables/useDebouncedEffect'
import CopyButton from './CopyButton.vue'

// 只有 AES 工具的密钥/IV 明确要求不落 localStorage；这里的 HMAC 密钥按验收要求的通用规则持久化
const input = usePersistedRef('hash:input', '')
const hmacKey = usePersistedRef('hash:hmacKey', '')

interface HashResults {
  md5: string
  sha1: string
  sha256: string
  sha512: string
}

const results = ref<HashResults>({ md5: '', sha1: '', sha256: '', sha512: '' })

function compute() {
  if (!input.value) {
    results.value = { md5: '', sha1: '', sha256: '', sha512: '' }
    return
  }
  // crypto-js 对普通字符串入参默认按 UTF-8 处理，中文等多字节字符无需额外转换
  if (hmacKey.value) {
    results.value = {
      md5: CryptoJS.HmacMD5(input.value, hmacKey.value).toString(),
      sha1: CryptoJS.HmacSHA1(input.value, hmacKey.value).toString(),
      sha256: CryptoJS.HmacSHA256(input.value, hmacKey.value).toString(),
      sha512: CryptoJS.HmacSHA512(input.value, hmacKey.value).toString(),
    }
  } else {
    results.value = {
      md5: CryptoJS.MD5(input.value).toString(),
      sha1: CryptoJS.SHA1(input.value).toString(),
      sha256: CryptoJS.SHA256(input.value).toString(),
      sha512: CryptoJS.SHA512(input.value).toString(),
    }
  }
}

useDebouncedEffect([input, hmacKey], compute)

const rows: Array<{ key: keyof HashResults; label: string }> = [
  { key: 'md5', label: 'MD5' },
  { key: 'sha1', label: 'SHA1' },
  { key: 'sha256', label: 'SHA256' },
  { key: 'sha512', label: 'SHA512' },
]
</script>

<template>
  <div class="hash-panel">
    <el-form label-width="90px">
      <el-form-item label="待计算内容">
        <textarea v-model="input" class="code-textarea" spellcheck="false" placeholder="输入需要计算哈希的文本…" />
      </el-form-item>
      <el-form-item label="HMAC 密钥">
        <el-input v-model="hmacKey" placeholder="留空则计算普通哈希，非空则计算 HMAC" clearable style="max-width: 360px" />
      </el-form-item>
    </el-form>

    <div class="hash-results">
      <div v-for="row in rows" :key="row.key" class="hash-row">
        <span class="hash-label">{{ row.label }}</span>
        <code class="hash-value">{{ results[row.key] || '—' }}</code>
        <CopyButton :text="results[row.key]" :label="row.label" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.hash-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.code-textarea {
  min-height: 100px;
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

.hash-results {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hash-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.hash-label {
  flex-shrink: 0;
  width: 70px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}

.hash-value {
  flex: 1;
  min-width: 0;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  overflow-wrap: break-word;
  word-break: break-all;
  color: var(--el-text-color-primary);
}
</style>
