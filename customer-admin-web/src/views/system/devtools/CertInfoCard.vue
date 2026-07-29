<script setup lang="ts">
import type { CertInfo } from '@/api/devtools'

// 单张证书的展示卡片：证书解析、密钥库条目链、匹配校验三处复用同一渲染
defineProps<{ cert: CertInfo; index?: number }>()

function formatTime(ms: number): string {
  return new Date(ms).toLocaleString('zh-CN')
}
</script>

<template>
  <el-card shadow="never" class="cert-card">
    <template #header>
      <div class="cert-header">
        <span class="cert-title">
          <template v-if="index != null">#{{ index + 1 }} </template>
          {{ cert.subject }}
        </span>
        <span class="cert-tags">
          <el-tag v-if="cert.ca" type="warning" size="small">CA</el-tag>
          <el-tag :type="cert.expired ? 'danger' : 'success'" size="small">
            {{ cert.expired ? '已过期/未生效' : `剩余 ${cert.daysRemaining} 天` }}
          </el-tag>
        </span>
      </div>
    </template>
    <el-descriptions :column="2" size="small" border>
      <el-descriptions-item label="使用者" :span="2">{{ cert.subject }}</el-descriptions-item>
      <el-descriptions-item label="颁发者" :span="2">{{ cert.issuer }}</el-descriptions-item>
      <el-descriptions-item label="序列号">{{ cert.serialNumberHex }}</el-descriptions-item>
      <el-descriptions-item label="版本">V{{ cert.version }}</el-descriptions-item>
      <el-descriptions-item label="生效时间">{{ formatTime(cert.notBeforeMs) }}</el-descriptions-item>
      <el-descriptions-item label="过期时间">{{ formatTime(cert.notAfterMs) }}</el-descriptions-item>
      <el-descriptions-item label="签名算法">{{ cert.sigAlgName }}</el-descriptions-item>
      <el-descriptions-item label="公钥">
        {{ cert.publicKeyAlgorithm }}<template v-if="cert.publicKeyBits > 0"> {{ cert.publicKeyBits }} bit</template>
      </el-descriptions-item>
      <el-descriptions-item label="SAN" :span="2">
        <template v-if="cert.subjectAlternativeNames.length > 0">
          <el-tag v-for="san in cert.subjectAlternativeNames" :key="san" size="small" class="san-tag">{{ san }}</el-tag>
        </template>
        <span v-else class="muted">-</span>
      </el-descriptions-item>
      <el-descriptions-item label="密钥用法" :span="2">
        <template v-if="cert.keyUsages.length > 0">
          <el-tag v-for="usage in cert.keyUsages" :key="usage" type="info" size="small" class="san-tag">{{ usage }}</el-tag>
        </template>
        <span v-else class="muted">-</span>
      </el-descriptions-item>
      <el-descriptions-item label="SHA-1 指纹" :span="2">
        <code class="fingerprint">{{ cert.sha1Fingerprint }}</code>
      </el-descriptions-item>
      <el-descriptions-item label="SHA-256 指纹" :span="2">
        <code class="fingerprint">{{ cert.sha256Fingerprint }}</code>
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.cert-card {
  margin-bottom: 12px;
}

.cert-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.cert-title {
  font-weight: 500;
  word-break: break-all;
}

.cert-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.san-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}

.fingerprint {
  font-size: 12px;
  word-break: break-all;
}

.muted {
  color: var(--el-text-color-secondary);
}
</style>
