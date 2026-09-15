<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import {
  exportKeystorePrivateKey, matchCertKey, parseCertPem, parseKeystore,
  type CertMatchResponse, type CertParseResponse, type KeystoreParseResponse, type PrivateKeyExportResponse,
} from '@/api/devtools'
import CrudLoadState from '@/components/CrudLoadState.vue'
import { useQueryState } from '@/composables/useQueryState'
import { useAuthSubmissionScope } from '@/composables/useAuthSubmissionScope'
import { usePersistedRef } from './composables/useToolStorage'
import { downloadText, safeFileBase } from './composables/useDownload'
import CertInfoCard from './CertInfoCard.vue'
import CopyButton from './CopyButton.vue'

type CertMode = 'parse' | 'match' | 'keystore'
const mode = usePersistedRef<CertMode>('cert:mode', 'parse')
const captureSubmission = useAuthSubmissionScope()

const pemContent = usePersistedRef('cert:pem', '')
const { data: parseResult, loading: parsing, error: parseError, load: runParse, reset: resetParse } =
  useQueryState<CertParseResponse | null>(() => parseCertPem(pemContent.value), () => null)

async function handleParse() {
  if (parsing.value) return
  if (!pemContent.value.trim()) { ElMessage.warning('请先粘贴 PEM 内容'); return }
  await runParse()
}
function clearParse() {
  pemContent.value = ''
  resetParse()
}

// 私钥与密码仅属于当前组件，不能进入本地输入持久化。
const matchCertPem = usePersistedRef('cert:matchCert', '')
const matchKeyPem = ref('')
const { data: matchResult, loading: matching, error: matchError, load: runMatch, reset: resetMatch } =
  useQueryState<CertMatchResponse | null>(() => matchCertKey(matchCertPem.value, matchKeyPem.value), () => null)

async function handleMatch() {
  if (matching.value) return
  if (!matchCertPem.value.trim() || !matchKeyPem.value.trim()) { ElMessage.warning('证书与私钥都要填写'); return }
  await runMatch()
}
function clearMatch() {
  matchCertPem.value = ''
  matchKeyPem.value = ''
  resetMatch()
}

interface KeystoreInput { file: File; password: string }
const keystorePassword = ref('')
const keystoreFileName = ref('')
const keystoreInput = shallowRef<KeystoreInput | null>(null)
const { data: keystoreSnapshot, loading: keystoreLoading, error: keystoreError,
  load: runKeystore, reset: resetKeystore } = useQueryState<{ parsed: KeystoreParseResponse; input: KeystoreInput } | null>(async () => {
    const input = keystoreInput.value!
    return { parsed: await parseKeystore(input.file, input.password), input }
  }, () => null)
const keystoreResult = computed(() => keystoreSnapshot.value?.parsed ?? null)

async function handleKeystoreUpload(options: { file: File }) {
  resetKeystore()
  closeKeyDialog()
  keystoreFileName.value = options.file.name
  keystoreInput.value = { file: options.file, password: keystorePassword.value }
  await runKeystore()
}
async function retryKeystore() {
  if (!keystoreInput.value || keystoreLoading.value) return
  keystoreInput.value = { file: keystoreInput.value.file, password: keystorePassword.value }
  await runKeystore()
}
function clearKeystore() {
  keystorePassword.value = ''
  keystoreFileName.value = ''
  keystoreInput.value = null
  resetKeystore()
  closeKeyDialog()
}

// 导出使用产生当前条目列表的文件和密码，确认期间的输入修改不能改变导出目标。
const keyDialogVisible = ref(false)
const confirmingExport = ref(false)
const exportRequest = shallowRef<{ input: KeystoreInput; alias: string } | null>(null)
const { data: keyResult, loading: keyExporting, error: keyError, load: runExport, reset: resetExport } =
  useQueryState<PrivateKeyExportResponse | null>(() => {
    const request = exportRequest.value!
    return exportKeystorePrivateKey(request.input.file, request.input.password, request.alias, '')
  }, () => null)

async function handleExportPrivateKey(alias: string) {
  const snapshot = keystoreSnapshot.value
  if (!snapshot || confirmingExport.value || keyExporting.value || keystoreLoading.value) return
  const identityIsCurrent = captureSubmission()
  const isCurrent = () => identityIsCurrent() && keystoreSnapshot.value === snapshot
  confirmingExport.value = true
  try {
    await ElMessageBox.confirm(
      '私钥将以未加密的 PKCS#8 PEM 明文返回并显示在页面上，请确认当前环境适合展示私钥。',
      `导出私钥：${alias}`,
      { type: 'warning', confirmButtonText: '确认导出', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消。
  } finally {
    confirmingExport.value = false
  }
  if (!isCurrent()) return
  exportRequest.value = { input: snapshot.input, alias }
  const result = await runExport()
  if (result) keyDialogVisible.value = true
}
function retryExport() {
  const alias = exportRequest.value?.alias
  if (alias) void handleExportPrivateKey(alias)
}
function handleDownloadKey() {
  if (keyResult.value) downloadText(keyResult.value.privateKeyPem, `${safeFileBase(keyResult.value.alias, 'private')}.key`)
}
function closeKeyDialog() {
  keyDialogVisible.value = false
  resetExport()
  exportRequest.value = null
}
watch(keyDialogVisible, visible => { if (!visible) closeKeyDialog() }, { flush: 'sync' })
watch(mode, closeKeyDialog)
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
      <CrudLoadState :error="parseError" :has-stale-data="!!parseResult" :loading="parsing" @retry="handleParse" />
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
      <CrudLoadState :error="matchError" :has-stale-data="!!matchResult" :loading="matching" @retry="handleMatch" />
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
      <CrudLoadState :error="keystoreError" :has-stale-data="!!keystoreResult" :loading="keystoreLoading" @retry="retryKeystore" />
      <CrudLoadState :error="keyError" :has-stale-data="!!keyResult" :loading="keyExporting" @retry="retryExport" />
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
                :disabled="confirmingExport || keyExporting || keystoreLoading"
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
