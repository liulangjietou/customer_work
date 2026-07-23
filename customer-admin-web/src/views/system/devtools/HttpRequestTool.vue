<script setup lang="ts">
import { computed, ref } from 'vue'
import hljs from 'highlight.js/lib/core'
import json from 'highlight.js/lib/languages/json'
import { sendHttpRequest, type HttpKeyValueItem, type HttpSendResponse } from '@/api/devtools'
import { usePersistedRef } from './composables/useToolStorage'
import CopyButton from './CopyButton.vue'
import KeyValueEditor from './KeyValueEditor.vue'

hljs.registerLanguage('json', json)

// 与后端 DevToolHttpSendRequest 的方法白名单保持一致
const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'] as const

/** 语义上无请求体的方法（后端同样会忽略其 body），Body 页给出提示 */
const NO_BODY_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

type BodyType = 'none' | 'json' | 'form' | 'raw'

const method = usePersistedRef<string>('http:method', 'GET')
const url = usePersistedRef('http:url', '')
const params = usePersistedRef<HttpKeyValueItem[]>('http:params', [])
const headers = usePersistedRef<HttpKeyValueItem[]>('http:headers', [])
const bodyType = usePersistedRef<BodyType>('http:bodyType', 'none')
const jsonBody = usePersistedRef('http:jsonBody', '')
const formItems = usePersistedRef<HttpKeyValueItem[]>('http:formItems', [])
const rawBody = usePersistedRef('http:rawBody', '')
const rawContentType = usePersistedRef('http:rawContentType', 'text/plain')

const activeRequestTab = ref('params')
const activeResponseTab = ref('body')
const sending = ref(false)
const response = ref<HttpSendResponse | null>(null)

const methodHasBody = computed(() => !NO_BODY_METHODS.has(method.value))

/** 过滤掉名称为空的行（编辑器允许留空行，发送时忽略） */
function effectiveItems(items: HttpKeyValueItem[]): HttpKeyValueItem[] {
  return items.filter((item) => item.name.trim() !== '')
}

/** Params 合并进 URL：保留 URL 上已有的 query，追加编辑器里的参数并做 URL 编码 */
function buildUrl(): string {
  const base = url.value.trim()
  const items = effectiveItems(params.value)
  if (items.length === 0) return base
  const search = items
    .map((item) => `${encodeURIComponent(item.name.trim())}=${encodeURIComponent(item.value)}`)
    .join('&')
  return base + (base.includes('?') ? '&' : '?') + search
}

/** 按 Body 类型自动补 Content-Type（用户已显式填写同名头时不覆盖） */
function buildHeaders(): HttpKeyValueItem[] {
  const items = effectiveItems(headers.value).map((item) => ({ name: item.name.trim(), value: item.value }))
  const hasContentType = items.some((item) => item.name.toLowerCase() === 'content-type')
  if (!hasContentType && methodHasBody.value) {
    if (bodyType.value === 'json') {
      items.push({ name: 'Content-Type', value: 'application/json' })
    } else if (bodyType.value === 'form') {
      items.push({ name: 'Content-Type', value: 'application/x-www-form-urlencoded' })
    } else if (bodyType.value === 'raw' && rawContentType.value.trim()) {
      items.push({ name: 'Content-Type', value: rawContentType.value.trim() })
    }
  }
  return items
}

function buildBody(): string | undefined {
  if (!methodHasBody.value || bodyType.value === 'none') return undefined
  if (bodyType.value === 'json') return jsonBody.value || undefined
  if (bodyType.value === 'form') {
    const items = effectiveItems(formItems.value)
    if (items.length === 0) return undefined
    return items
      .map((item) => `${encodeURIComponent(item.name.trim())}=${encodeURIComponent(item.value)}`)
      .join('&')
  }
  return rawBody.value || undefined
}

function formatJsonBody() {
  try {
    jsonBody.value = JSON.stringify(JSON.parse(jsonBody.value), null, 2)
  } catch {
    ElMessage.error('JSON 格式非法，无法格式化')
  }
}

async function send() {
  const target = url.value.trim()
  if (!target) {
    ElMessage.error('请输入请求 URL')
    return
  }
  sending.value = true
  response.value = null
  try {
    response.value = await sendHttpRequest({
      method: method.value,
      url: buildUrl(),
      headers: buildHeaders(),
      body: buildBody(),
    })
    activeResponseTab.value = 'body'
  } catch {
    // 业务失败（SSRF 拦截/参数非法等）request.ts 拦截器已统一弹出错误消息，这里无需重复处理
  } finally {
    sending.value = false
  }
}

const statusTagType = computed(() => {
  const code = response.value?.statusCode
  if (!code) return 'danger'
  if (code < 300) return 'success'
  if (code < 400) return 'warning'
  return 'danger'
})

/** 响应体展示：是合法 JSON 时自动美化 + 高亮，否则按纯文本原样展示 */
const prettyBody = computed(() => {
  const body = response.value?.body
  if (!body) return { text: '', highlighted: '', isJson: false }
  try {
    const text = JSON.stringify(JSON.parse(body), null, 2)
    return { text, highlighted: hljs.highlight(text, { language: 'json' }).value, isJson: true }
  } catch {
    return { text: body, highlighted: '', isJson: false }
  }
})

const responseHeaderRows = computed(() => {
  const map = response.value?.headers
  if (!map) return []
  return Object.entries(map)
    .map(([name, values]) => ({ name, value: values.join(', ') }))
    .sort((a, b) => a.name.localeCompare(b.name))
})

function formatBytes(bytes: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}
</script>

<template>
  <div class="http-tool">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="请求由后端服务代理发出（不受浏览器 CORS 限制）；出于安全策略默认拦截内网/环回地址，可通过 admin.system-tool.http.allowed-hosts 白名单放行。"
      class="tool-tip"
    />

    <div class="request-line">
      <el-select v-model="method" class="method-select">
        <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
      </el-select>
      <el-input
        v-model="url"
        placeholder="https://example.com/api/path"
        class="url-input"
        clearable
        @keyup.enter="send"
      />
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
    </div>

    <el-tabs v-model="activeRequestTab" class="request-tabs">
      <el-tab-pane :label="`Params (${params.filter((p) => p.name.trim()).length})`" name="params">
        <div class="pane-hint">发送时自动 URL 编码后追加到地址栏 query，URL 上已有的参数保留。</div>
        <KeyValueEditor v-model="params" name-placeholder="参数名" value-placeholder="参数值" />
      </el-tab-pane>
      <el-tab-pane :label="`Headers (${headers.filter((h) => h.name.trim()).length})`" name="headers">
        <div class="pane-hint">
          Host / Content-Length / Connection 等由客户端自动生成的请求头会被忽略；未显式填写 Content-Type 时按 Body
          类型自动补齐。
        </div>
        <KeyValueEditor v-model="headers" name-placeholder="请求头名称" value-placeholder="请求头值" />
      </el-tab-pane>
      <el-tab-pane label="Body" name="body">
        <el-alert
          v-if="!methodHasBody"
          type="info"
          :closable="false"
          :title="`${method} 请求不发送请求体`"
          class="no-body-tip"
        />
        <template v-else>
          <el-radio-group v-model="bodyType" class="body-type-group">
            <el-radio-button value="none">none</el-radio-button>
            <el-radio-button value="json">JSON</el-radio-button>
            <el-radio-button value="form">form-urlencoded</el-radio-button>
            <el-radio-button value="raw">raw</el-radio-button>
          </el-radio-group>

          <template v-if="bodyType === 'json'">
            <div class="body-toolbar">
              <el-button size="small" @click="formatJsonBody">格式化</el-button>
            </div>
            <el-input v-model="jsonBody" type="textarea" :rows="8" placeholder='{"key": "value"}' />
          </template>
          <KeyValueEditor
            v-else-if="bodyType === 'form'"
            v-model="formItems"
            name-placeholder="字段名"
            value-placeholder="字段值"
          />
          <template v-else-if="bodyType === 'raw'">
            <div class="body-toolbar">
              <span class="raw-ct-label">Content-Type</span>
              <el-input v-model="rawContentType" placeholder="text/plain" class="raw-ct-input" />
            </div>
            <el-input v-model="rawBody" type="textarea" :rows="8" placeholder="请求体原始内容" />
          </template>
        </template>
      </el-tab-pane>
    </el-tabs>

    <template v-if="response">
      <el-divider content-position="left">响应</el-divider>

      <el-alert v-if="response.error" type="error" :closable="false" show-icon :title="response.error" />
      <template v-else>
        <div class="response-meta">
          <el-tag :type="statusTagType" size="large">{{ response.statusCode }}</el-tag>
          <span class="meta-item">耗时 {{ response.durationMs }} ms</span>
          <span class="meta-item">大小 {{ formatBytes(response.bodyBytes) }}</span>
          <el-tag v-if="response.bodyTruncated" type="warning">响应体超过 1MB，已截断展示</el-tag>
          <span v-if="response.redirectLocation" class="meta-item">
            重定向至：{{ response.redirectLocation }}（工具不自动跟随，如需继续请手动更换 URL）
          </span>
        </div>

        <el-tabs v-model="activeResponseTab">
          <el-tab-pane label="响应体" name="body">
            <div class="body-toolbar">
              <CopyButton :text="prettyBody.text" label="响应体" />
            </div>
            <el-scrollbar max-height="420px" class="response-body">
              <pre v-if="prettyBody.isJson" class="body-pre hljs" v-html="prettyBody.highlighted"></pre>
              <pre v-else class="body-pre">{{ prettyBody.text || '(空响应体)' }}</pre>
            </el-scrollbar>
          </el-tab-pane>
          <el-tab-pane :label="`响应头 (${responseHeaderRows.length})`" name="headers">
            <el-table :data="responseHeaderRows" size="small" border>
              <el-table-column prop="name" label="名称" width="280" />
              <el-table-column prop="value" label="值" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </template>
  </div>
</template>

<style scoped>
.http-tool {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tool-tip :deep(.el-alert__title) {
  line-height: 1.6;
}

.request-line {
  display: flex;
  gap: 8px;
}

.method-select {
  width: 120px;
  flex-shrink: 0;
}

.url-input {
  flex: 1;
}

.pane-hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.no-body-tip {
  margin-top: 4px;
}

.body-type-group {
  margin-bottom: 10px;
}

.body-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.raw-ct-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.raw-ct-input {
  width: 260px;
}

.response-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.meta-item {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.response-body {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.body-pre {
  margin: 0;
  padding: 12px;
  font-family: var(--el-font-family-mono, 'SFMono-Regular', Consolas, monospace);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
