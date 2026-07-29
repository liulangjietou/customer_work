<script setup lang="ts">
import { ref } from 'vue'
import {
  exportKeystorePrivateKey,
  matchCertKey,
  parseCertPem,
  parseKeystore,
  type CertMatchResponse,
  type CertParseResponse,
  type KeystoreParseResponse,
  type PrivateKeyExportResponse,
} from '@/api/devtools'
import { usePersistedRef } from './composables/useToolStorage'
import { downloadText, safeFileBase } from './composables/useDownload'
import CertInfoCard from './CertInfoCard.vue'
import CopyButton from './CopyButton.vue'

type CertMode = 'parse' | 'match' | 'keystore'

const mode = usePersistedRef<CertMode>('cert:mode', 'parse')

// ---------- 证书 / CSR 解析 ----------

const pemContent = usePersistedRef('cert:pem', '')
const parsing = ref(false)
const parseResult = ref<CertParseResponse | null>(null)

async function handleParse() {
  if (!pemContent.value.trim()) {
    ElMessage.warning('请先粘贴 PEM 内容')
    return
  }
  parsing.value = true
  try {
    parseResult.value = await parseCertPem(pemContent.value)
  } finally {
    parsing.value = false
  }
}

function clearParse() {
  pemContent.value = ''
  parseResult.value = null
}

// ---------- 私钥匹配校验 ----------
// 私钥属敏感信息，与 AES 工具的密钥同等对待：用普通 ref，不进 localStorage

const matchCertPem = usePersistedRef('cert:matchCert', '')
const matchKeyPem = ref('')
const matching = ref(false)
const matchResult = ref<CertMatchResponse | null>(null)

async function handleMatch() {
  if (!matchCertPem.value.trim() || !matchKeyPem.value.trim()) {
    ElMessage.warning('证书与私钥都要填写')
    return
  }
  matching.value = true
  try {
    matchResult.value = await matchCertKey(matchCertPem.value, matchKeyPem.value)
  } finally {
    matching.value = false
  }
}

function clearMatch() {
  matchCertPem.value = ''
  matchKeyPem.value = ''
  matchResult.value = null
}

// ---------- 密钥库 ----------
// 库密码同样不持久化

const keystorePassword = ref('')
const keystoreFileName = ref('')
const keystoreLoading = ref(false)
const keystoreResult = ref<KeystoreParseResponse | null>(null)
// 留住文件对象供"导出私钥"二次上传（私钥不随条目列举返回，需要显式再发一次请求）
const keystoreFile = ref<File | null>(null)

async function handleKeystoreUpload(options: { file: File }) {
  keystoreLoading.value = true
  keystoreFileName.value = options.file.name
  keystoreFile.value = options.file
  try {
    keystoreResult.value = await parseKeystore(options.file, keystorePassword.value)
  } finally {
    keystoreLoading.value = false
  }
}

function clearKeystore() {
  keystorePassword.value = ''
  keystoreFileName.value = ''
  keystoreResult.value = null
  keystoreFile.value = null
  closeKeyDialog()
}

// ---------- 私钥导出 ----------
// 私钥只在对话框打开期间存在于内存，关闭即丢弃，不持久化、不写回列表数据

const keyDialogVisible = ref(false)
const keyExporting = ref(false)
const keyResult = ref<PrivateKeyExportResponse | null>(null)

async function handleExportPrivateKey(alias: string) {
  if (!keystoreFile.value) {
    ElMessage.warning('请先上传密钥库文件')
    return
  }
  try {
    await ElMessageBox.confirm(
      '私钥将以未加密的 PKCS#8 PEM 明文返回并显示在页面上，请确认当前环境适合展示私钥。',
      `导出私钥：${alias}`,
      { type: 'warning', confirmButtonText: '确认导出', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  keyExporting.value = true
  try {
    // 条目私钥密码留空，后端回落库密码（PKCS12 惯例两者相同）
    keyResult.value = await exportKeystorePrivateKey(keystoreFile.value, keystorePassword.value, alias, '')
    keyDialogVisible.value = true
  } finally {
    keyExporting.value = false
  }
}

function handleDownloadKey() {
  if (!keyResult.value) {
    return
  }
  downloadText(keyResult.value.privateKeyPem, `${safeFileBase(keyResult.value.alias, 'private')}.key`)
}

function closeKeyDialog() {
  keyDialogVisible.value = false
  keyResult.value = null
}
</script>

<template>
  <div class="cert-tool">
    <el-alert type="info" :closable="false" show-icon class="notice">
      证书解析在<b>后端 Java</b> 完成（浏览器无 X.509 原生解析能力）。所有输入只在请求内存中解析，
      不落库、不写日志；私钥与密钥库密码不做本地持久化，刷新页面即清空。
    </el-alert>

    <el-radio-group v-model="mode" class="mode-switch">
      <el-radio-button value="parse">证书 / CSR 解析</el-radio-button>
      <el-radio-button value="match">私钥匹配校验</el-radio-button>
      <el-radio-button value="keystore">PFX / JKS 密钥库</el-radio-button>
    </el-radio-group>

    <!-- 证书 / CSR 解析 -->
    <div v-if="mode === 'parse'">
      <el-input
        v-model="pemContent"
        type="textarea"
        :rows="10"
        placeholder="粘贴 -----BEGIN CERTIFICATE----- 证书或证书链，也支持 -----BEGIN CERTIFICATE REQUEST----- (CSR)；可一次粘贴多段"
      />
      <div class="actions">
        <el-button type="primary" :loading="parsing" @click="handleParse">解析</el-button>
        <el-button @click="clearParse">清空</el-button>
      </div>

      <template v-if="parseResult">
        <template v-if="parseResult.certificates.length > 0">
          <h4 class="section-title">证书（{{ parseResult.certificates.length }}）</h4>
          <CertInfoCard
            v-for="(cert, i) in parseResult.certificates"
            :key="cert.sha256Fingerprint + i"
            :cert="cert"
            :index="parseResult.certificates.length > 1 ? i : undefined"
          />
        </template>
        <template v-if="parseResult.csrs.length > 0">
          <h4 class="section-title">证书签名请求（{{ parseResult.csrs.length }}）</h4>
          <el-card v-for="(csr, i) in parseResult.csrs" :key="i" shadow="never" class="csr-card">
            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item label="申请主题" :span="2">{{ csr.subject }}</el-descriptions-item>
              <el-descriptions-item label="公钥">
                {{ csr.publicKeyAlgorithm }}<template v-if="csr.publicKeyBits > 0"> {{ csr.publicKeyBits }} bit</template>
              </el-descriptions-item>
              <el-descriptions-item label="签名算法">{{ csr.sigAlgName }}</el-descriptions-item>
              <el-descriptions-item label="SAN" :span="2">
                <template v-if="csr.subjectAlternativeNames.length > 0">
                  <el-tag v-for="san in csr.subjectAlternativeNames" :key="san" size="small" class="san-tag">{{ san }}</el-tag>
                </template>
                <span v-else class="muted">-</span>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </template>
      </template>
    </div>

    <!-- 私钥匹配校验 -->
    <div v-else-if="mode === 'match'">
      <div class="match-inputs">
        <div class="match-col">
          <div class="field-label">证书 PEM</div>
          <el-input v-model="matchCertPem" type="textarea" :rows="9" placeholder="-----BEGIN CERTIFICATE-----" />
        </div>
        <div class="match-col">
          <div class="field-label">私钥 PEM（不持久化，支持 PKCS#8 / PKCS#1 / SEC1，不支持加密私钥）</div>
          <el-input v-model="matchKeyPem" type="textarea" :rows="9" placeholder="-----BEGIN PRIVATE KEY----- 或 -----BEGIN RSA/EC PRIVATE KEY-----" />
        </div>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="matching" @click="handleMatch">校验匹配</el-button>
        <el-button @click="clearMatch">清空</el-button>
      </div>
      <el-result
        v-if="matchResult"
        :icon="matchResult.matched ? 'success' : 'error'"
        :title="matchResult.matched ? '私钥与证书配对' : '私钥与证书不配对'"
        :sub-title="`${matchResult.reason}（公钥算法：${matchResult.publicKeyAlgorithm}）`"
      />
    </div>

    <!-- 密钥库 -->
    <div v-else>
      <div class="keystore-form">
        <el-input
          v-model="keystorePassword"
          type="password"
          show-password
          placeholder="密钥库密码（不持久化，无密码留空）"
          style="width: 320px"
        />
        <el-upload :show-file-list="false" :http-request="handleKeystoreUpload" accept=".pfx,.p12,.jks,.keystore">
          <el-button type="primary" :loading="keystoreLoading">选择 .pfx / .p12 / .jks 文件</el-button>
        </el-upload>
        <el-button @click="clearKeystore">清空</el-button>
      </div>
      <div v-if="keystoreFileName" class="file-hint">已选文件：{{ keystoreFileName }}</div>

      <template v-if="keystoreResult">
        <h4 class="section-title">
          密钥库类型：{{ keystoreResult.keystoreType }} · 条目 {{ keystoreResult.entries.length }} 个
        </h4>
        <el-collapse>
          <el-collapse-item v-for="entry in keystoreResult.entries" :key="entry.alias" :name="entry.alias">
            <template #title>
              <span class="entry-title">
                {{ entry.alias }}
                <el-tag :type="entry.entryType === 'PRIVATE_KEY' ? 'success' : 'info'" size="small">
                  {{ entry.entryType === 'PRIVATE_KEY' ? '含私钥' : '仅证书' }}
                </el-tag>
                <span class="muted">证书链 {{ entry.chain.length }} 张</span>
              </span>
            </template>
            <div v-if="entry.entryType === 'PRIVATE_KEY'" class="entry-actions">
              <el-button
                link
                type="primary"
                size="small"
                :loading="keyExporting"
                @click="handleExportPrivateKey(entry.alias)"
              >
                <el-icon><Key /></el-icon>
                导出私钥 PEM
              </el-button>
              <span class="muted">配合下方证书 PEM 即可得到部署用的 crt + key</span>
            </div>
            <CertInfoCard
              v-for="(cert, i) in entry.chain"
              :key="cert.sha256Fingerprint + i"
              :cert="cert"
              :index="entry.chain.length > 1 ? i : undefined"
            />
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>

    <!-- 私钥只在本对话框存续期间留在内存，关闭即丢弃 -->
    <el-dialog
      v-model="keyDialogVisible"
      title="私钥导出"
      width="640px"
      @closed="closeKeyDialog"
    >
      <template v-if="keyResult">
        <el-alert type="warning" :closable="false" show-icon class="notice">
          未加密的 PKCS#8 私钥明文。关闭本窗口后页面不再保留，请妥善保存并注意不要留在剪贴板里。
        </el-alert>
        <div class="key-meta">
          <span>别名：<b>{{ keyResult.alias }}</b></span>
          <span>算法：<b>{{ keyResult.algorithm }}</b></span>
        </div>
        <div class="pem-actions">
          <CopyButton :text="keyResult.privateKeyPem" label="私钥 PEM" />
          <el-button link type="primary" size="small" @click="handleDownloadKey">
            <el-icon><Download /></el-icon>
            下载 .key
          </el-button>
        </div>
        <pre class="pem-block">{{ keyResult.privateKeyPem }}</pre>
      </template>
      <template #footer>
        <el-button type="primary" @click="closeKeyDialog">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.notice {
  margin-bottom: 12px;
}

.entry-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.key-meta {
  display: flex;
  gap: 20px;
  margin-bottom: 8px;
  font-size: 13px;
}

.pem-actions {
  display: flex;
  gap: 12px;
  margin-bottom: 6px;
}

.pem-block {
  margin: 0;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}

.mode-switch {
  margin-bottom: 12px;
}

.actions {
  margin: 12px 0;
  display: flex;
  gap: 8px;
}

.section-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.match-inputs {
  display: flex;
  gap: 12px;
}

.match-col {
  flex: 1;
  min-width: 0;
}

.field-label {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.keystore-form {
  display: flex;
  gap: 8px;
  align-items: center;
}

.file-hint {
  margin-top: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.entry-title {
  display: flex;
  gap: 8px;
  align-items: center;
}

.csr-card {
  margin-bottom: 12px;
}

.san-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
