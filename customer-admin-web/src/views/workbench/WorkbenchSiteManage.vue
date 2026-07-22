<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance, UploadFile } from 'element-plus'
import {
  createWorkbenchSite,
  deleteWorkbenchSite,
  generateWorkbenchScript,
  getWorkbenchSiteSecret,
  pageWorkbenchSites,
  updateWorkbenchSite,
} from '@/api/workbench'
import { useCrudPage } from '@/composables/useCrudPage'
import { copyText } from '@/views/system/devtools/composables/useCopy'
import { parseUserscript } from '@/utils/scriptImport'
import WorkbenchTokenDialog from './WorkbenchTokenDialog.vue'
import type { PageQuery, WorkbenchSiteSaveRequest, WorkbenchSiteVO } from '@/types/api'

// url 校验正则与后端 @Pattern 保持一致
const URL_PATTERN = /^https?:\/\/.+/

const formRef = ref<FormInstance>()
const secretLoadingId = ref<number | null>(null)

const {
  loading, list, total, query,
  dialogVisible, dialogMode, form,
  loadList, handleSearch, openCreate, openEdit, handleSubmit, handleDelete,
} = useCrudPage<WorkbenchSiteVO, PageQuery, WorkbenchSiteSaveRequest>({
  page: pageWorkbenchSites,
  formRef,
  create: createWorkbenchSite,
  update: updateWorkbenchSite,
  remove: (row) => deleteWorkbenchSite(row.id),
  initQuery: () => ({ pageNum: 1, pageSize: 10, keyword: '' }),
  initForm: () => ({
    name: '', category: '', url: '', account: '', password: '', remark: '', enabled: true,
    usernameSelector: '', passwordSelector: '', submitSelector: '',
    fillMode: 'auto', submitMode: 'click', initDelayMs: 500, submitDelayMs: 300,
  }),
  toForm: (row) => ({
    name: row.name, category: row.category, url: row.url, account: row.account, password: '',
    remark: row.remark, enabled: row.enabled,
    usernameSelector: row.usernameSelector, passwordSelector: row.passwordSelector,
    submitSelector: row.submitSelector, fillMode: row.fillMode, submitMode: row.submitMode,
    initDelayMs: row.initDelayMs, submitDelayMs: row.submitDelayMs,
  }),
  deleteConfirm: (row) => `确认删除站点「${row.name}」？`,
})

/** 在新标签页打开站点地址（noopener 防止被打开页反向操纵本页）。 */
function openSite(row: WorkbenchSiteVO) {
  window.open(row.url, '_blank', 'noopener')
}

/** 复制明文密码：先向后端换取解密后的明文（敏感读接口），再写入剪贴板。 */
async function copySecret(row: WorkbenchSiteVO) {
  secretLoadingId.value = row.id
  try {
    const secret = await getWorkbenchSiteSecret(row.id)
    await copyText(secret, '密码')
  } catch {
    // 错误提示已由 request.ts 拦截器统一弹出，这里不重复弹
  } finally {
    secretLoadingId.value = null
  }
}

// ===== 令牌管理 =====
const tokenDialogVisible = ref(false)

// ===== 生成登录脚本 =====
const scriptDialogVisible = ref(false)
const scriptName = ref('')
const scriptExpireDays = ref<number | null>(90)
const generating = ref(false)

const EXPIRE_OPTIONS = [
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
  { label: '永不过期', value: null },
]

function openScriptDialog() {
  scriptName.value = 'ScriptCat 登录脚本'
  scriptExpireDays.value = 90
  scriptDialogVisible.value = true
}

async function generateScript() {
  if (!scriptName.value.trim()) {
    ElMessage.warning('请填写令牌用途')
    return
  }
  generating.value = true
  try {
    const script = await generateWorkbenchScript({ name: scriptName.value, expireDays: scriptExpireDays.value })
    // Blob 触发浏览器下载 .user.js
    const blob = new Blob([script], { type: 'text/javascript;charset=utf-8' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = 'workbench-login.user.js'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(link.href)
    scriptDialogVisible.value = false
    ElMessage.success('脚本已下载，请拖入 ScriptCat 安装；令牌已内嵌，请勿外传')
  } finally {
    generating.value = false
  }
}

// ===== 从 ScriptCat 脚本导入预填（仅新增时）=====
const importDialogVisible = ref(false)
const importText = ref('')

function openImport() {
  importText.value = ''
  importDialogVisible.value = true
}

/** el-upload 选中文件后读其文本填入文本框（不真正上传，纯本地解析）。 */
async function onPickFile(file: UploadFile) {
  if (file.raw) {
    importText.value = await file.raw.text()
  }
}

/** 解析脚本并把命中的字段预填进新增表单，解析不出的留空交用户核对。 */
function applyImport() {
  if (!importText.value.trim()) {
    ElMessage.warning('请粘贴脚本内容或选择脚本文件')
    return
  }
  const r = parseUserscript(importText.value)
  if (r.name) form.name = r.name
  if (r.url) form.url = r.url
  if (r.account) form.account = r.account
  if (r.password) form.password = r.password
  if (r.usernameSelector) form.usernameSelector = r.usernameSelector
  if (r.passwordSelector) form.passwordSelector = r.passwordSelector
  if (r.submitSelector) form.submitSelector = r.submitSelector
  form.fillMode = r.fillMode
  form.submitMode = r.submitMode
  importDialogVisible.value = false
  ElMessage.success(`已解析 ${r.matchedCount} 项并预填，请核对后保存`)
  r.warnings.forEach((w) => ElMessage.warning(w))
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称/分类搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'workbench-site:add'" type="primary" @click="openCreate">新增站点</el-button>
        <div class="toolbar-right">
          <el-button @click="tokenDialogVisible = true">我的令牌</el-button>
          <el-button type="success" @click="openScriptDialog">生成登录脚本</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="name" label="名称" width="160" show-overflow-tooltip />
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.category" type="info">{{ row.category }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="openSite(row)">{{ row.url }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="账号" width="180">
          <template #default="{ row }">
            <span>{{ row.account || '-' }}</span>
            <el-button v-if="row.account" link type="primary" @click="copyText(row.account, '账号')">复制</el-button>
          </template>
        </el-table-column>
        <el-table-column label="密码" width="170">
          <template #default="{ row }">
            <span>{{ row.hasPassword ? row.passwordMasked : '-' }}</span>
            <el-button
              link
              type="primary"
              :disabled="!row.hasPassword"
              :loading="secretLoadingId === row.id"
              @click="copySecret(row)"
            >
              复制密码
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSite(row)">打开</el-button>
            <el-button v-permission="'workbench-site:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'workbench-site:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="loadList"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增站点' : '编辑站点'" width="600px">
      <el-alert v-if="dialogMode === 'create'" type="info" :closable="false" style="margin-bottom: 12px">
        <template #title>
          已有 ScriptCat / Tampermonkey 登录脚本？
          <el-button link type="primary" @click="openImport">点此导入自动预填</el-button>
        </template>
      </el-alert>
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="名称" prop="name" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category!" placeholder="如 git / jenkins / oa" />
        </el-form-item>
        <el-form-item
          label="地址"
          prop="url"
          :rules="[
            { required: true, message: '请输入访问地址' },
            { pattern: URL_PATTERN, message: 'url 必须以 http:// 或 https:// 开头' },
          ]"
        >
          <el-input v-model="form.url" placeholder="如 https://git.internal" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.account!" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password!" type="password" show-password :placeholder="dialogMode === 'edit' ? '留空则不修改' : '可留空'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>

        <el-collapse>
          <el-collapse-item name="advanced">
            <template #title>
              <span class="advanced-title">自动登录高级配置（可选，留空自动识别）</span>
            </template>
            <el-form-item label="用户名框">
              <el-input v-model="form.usernameSelector!" placeholder='CSS 选择器，如 input[name="username"]；留空自动' />
            </el-form-item>
            <el-form-item label="密码框">
              <el-input v-model="form.passwordSelector!" placeholder="留空默认 input[type=password]" />
            </el-form-item>
            <el-form-item label="登录按钮">
              <el-input v-model="form.submitSelector!" placeholder='如 button[type="submit"]；留空自动' />
            </el-form-item>
            <el-form-item label="填充模式">
              <el-select v-model="form.fillMode!" style="width: 100%">
                <el-option label="auto（原生 setter 一次性，通用）" value="auto" />
                <el-option label="typing（逐字模拟，顽固 React 如 Kibana）" value="typing" />
              </el-select>
            </el-form-item>
            <el-form-item label="提交方式">
              <el-select v-model="form.submitMode!" style="width: 100%">
                <el-option label="click（点击登录按钮）" value="click" />
                <el-option label="formSubmit（提交表单，如 Phabricator）" value="formSubmit" />
              </el-select>
            </el-form-item>
            <el-form-item label="启动延迟">
              <el-input-number v-model="form.initDelayMs!" :min="0" :step="100" /> <span class="unit">毫秒（进页面到开始填充）</span>
            </el-form-item>
            <el-form-item label="提交延迟">
              <el-input-number v-model="form.submitDelayMs!" :min="0" :step="100" /> <span class="unit">毫秒（填完到点击登录）</span>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 从脚本导入 -->
    <el-dialog v-model="importDialogVisible" title="从 ScriptCat 脚本导入" width="640px" append-to-body>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="脚本仅在浏览器本地解析、不会上传；解析出的地址/账号/密码/选择器会预填到新增表单，请核对后保存。"
        style="margin-bottom: 12px"
      />
      <el-upload :auto-upload="false" :show-file-list="false" accept=".js" :on-change="onPickFile">
        <el-button>选择脚本文件（.js）</el-button>
        <template #tip><span class="import-tip">或直接把脚本内容粘贴到下方</span></template>
      </el-upload>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="12"
        placeholder="粘贴 ScriptCat / Tampermonkey 脚本全文（含 // ==UserScript== 头）"
        class="import-textarea"
        style="margin-top: 12px"
      />
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="applyImport">解析并预填</el-button>
      </template>
    </el-dialog>

    <!-- 令牌管理 -->
    <WorkbenchTokenDialog v-model:visible="tokenDialogVisible" />

    <!-- 生成登录脚本 -->
    <el-dialog v-model="scriptDialogVisible" title="生成登录脚本" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="将为你签发一个内嵌令牌的通用脚本，下载后拖入 ScriptCat 安装即可。新增站点后请重新生成覆盖安装。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="80px">
        <el-form-item label="令牌用途" required>
          <el-input v-model="scriptName" placeholder="如：我的 Chrome ScriptCat" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="scriptExpireDays" style="width: 100%">
            <el-option v-for="opt in EXPIRE_OPTIONS" :key="String(opt.value)" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scriptDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="generateScript">生成并下载</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.advanced-title {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.import-tip {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.import-textarea :deep(textarea) {
  font-family: var(--el-font-family-mono, monospace);
}
</style>
