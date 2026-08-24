<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { createMcp, deleteMcp, getMcp, MCP_SECRET_PLACEHOLDER, pageMcps, testMcpConnectivity, updateMcp } from '@/api/mcp'
import McpDebugDialog from '@/components/McpDebugDialog.vue'
import { useAuthStore } from '@/store/auth'
import type { McpSaveRequest, McpVO, PageQuery } from '@/types/api'

const auth = useAuthStore()
const loading = ref(false)
const list = ref<McpVO[]>([])
const total = ref(0)
const query = reactive<PageQuery>({ pageNum: 1, pageSize: 10, keyword: '' })
const testingId = ref<number | null>(null)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit' | 'copy'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<McpSaveRequest>({
  mcpName: '', mcpType: 'sse', config: '', description: '', secretExpiresAt: null,
  allowedSubjectTypes: ['ADMIN_USER'], status: 1,
})

const subjectTypeOptions = [
  { value: 'ADMIN_USER', label: '后台用户' },
  { value: 'USER', label: '终端用户' },
  { value: 'API_KEY', label: 'API Key 接入方' },
  { value: 'IP', label: '匿名 IP（谨慎开放）' },
] as const

const testStatusMap: Record<number, { label: string; type: 'info' | 'success' | 'danger' }> = {
  0: { label: '未测试', type: 'info' },
  1: { label: '连通成功', type: 'success' },
  2: { label: '连通失败', type: 'danger' },
}

const configPlaceholder = computed(() => {
  if (form.mcpType === 'stdio') {
    return '{"command": "python", "args": ["-m", "mcp_server_git"]}'
  }
  if (form.mcpType === 'http') {
    return '{"url": "https://mcp.example.com/mcp"}'
  }
  return '{"url": "https://mcp.example.com/sse"}'
})

function validateConfigJson(_rule: unknown, value: string, callback: (error?: Error) => void) {
  try {
    JSON.parse(value)
    callback()
  } catch {
    callback(new Error('config 必须是合法 JSON'))
  }
}

async function loadList() {
  loading.value = true
  try {
    const result = await pageMcps(query)
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  loadList()
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    mcpName: '', mcpType: 'sse', config: '', description: '', secretExpiresAt: null,
    allowedSubjectTypes: ['ADMIN_USER'], status: 1,
  })
  dialogVisible.value = true
}

async function openEdit(row: McpVO) {
  const detail = await getMcp(row.id)
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    mcpName: detail.mcpName,
    mcpType: detail.mcpType,
    config: detail.config,
    description: detail.description,
    secretExpiresAt: detail.credential?.expiresAt ?? null,
    allowedSubjectTypes: [...detail.allowedSubjectTypes],
    status: detail.status,
  })
  dialogVisible.value = true
}

/** 复制必须重新提供详情里被脱敏的 secret，禁止把占位符当作真实凭据写入新记录。 */
async function openCopy(row: McpVO) {
  const detail = await getMcp(row.id)
  dialogMode.value = 'copy'
  editingId.value = null
  Object.assign(form, {
    mcpName: `${detail.mcpName}-副本`,
    mcpType: detail.mcpType,
    config: detail.config,
    description: detail.description,
    secretExpiresAt: null,
    allowedSubjectTypes: [...detail.allowedSubjectTypes],
    status: detail.status,
  })
  dialogVisible.value = true
  if (detail.config.includes(MCP_SECRET_PLACEHOLDER)) {
    ElMessage.warning('配置中的 secret 已脱敏，复制前必须将占位符替换为真实凭据')
  }
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value !== 'edit' && form.config.includes(MCP_SECRET_PLACEHOLDER)) {
    ElMessage.error('新建或复制 MCP 必须重新提供 secret，不能提交脱敏占位符')
    return
  }
  if (dialogMode.value === 'edit' && editingId.value) {
    await updateMcp(editingId.value, form)
    ElMessage.success('保存成功')
  } else {
    await createMcp(form)
    ElMessage.success(dialogMode.value === 'copy' ? '复制成功' : '新建成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: McpVO) {
  await ElMessageBox.confirm(`确认删除 MCP「${row.mcpName}」？`, '提示', { type: 'warning' })
  try {
    await deleteMcp(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch {
    // 引用校验失败的提示已由 axios 拦截器统一弹出
  }
}

async function handleTest(row: McpVO) {
  testingId.value = row.id
  try {
    const result = await testMcpConnectivity(row.id)
    if (result.testStatus === 1) {
      ElMessage.success('连通性测试成功')
    } else {
      ElMessage.error(result.message || '连通性测试失败')
    }
    await loadList()
  } finally {
    testingId.value = null
  }
}

const debugVisible = ref(false)
const debugMcpId = ref<number | null>(null)
const debugMcpName = ref('')

function openDebug(row: McpVO) {
  debugMcpId.value = row.id
  debugMcpName.value = row.mcpName
  debugVisible.value = true
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card>
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="按名称搜索" style="width: 220px" clearable @keyup.enter="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'mcp:add'" type="primary" @click="openCreate">新建 MCP</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column prop="mcpName" label="名称" />
        <el-table-column prop="mcpType" label="类型" width="100" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连通性" width="140">
          <template #default="{ row }">
            <el-tag :type="testStatusMap[row.testStatus]?.type ?? 'info'">{{ testStatusMap[row.testStatus]?.label ?? '未测试' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="凭据" width="150">
          <template #default="{ row }">
            <el-tag :type="row.credential?.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.credential ? `${row.credential.status} · v${row.credential.currentVersion}` : '无敏感参数' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="testingId === row.id" @click="handleTest(row)">测试连通性</el-button>
            <el-button v-permission="'mcp:edit'" link type="primary" @click="openDebug(row)">调试</el-button>
            <el-button v-permission="'mcp:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="auth.hasPermission('mcp:add') && auth.hasPermission('mcp:edit')" link type="primary" @click="openCopy(row)">复制</el-button>
            <el-button v-permission="'mcp:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'edit' ? '编辑 MCP' : dialogMode === 'copy' ? '复制 MCP' : '新建 MCP'" width="520px">
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="名称" prop="mcpName" :rules="[{ required: true, message: '请输入名称' }]">
          <el-input v-model="form.mcpName" />
        </el-form-item>
        <el-form-item label="类型" prop="mcpType">
          <el-radio-group v-model="form.mcpType">
            <el-radio value="sse">sse</el-radio>
            <el-radio value="http">http</el-radio>
            <el-radio value="stdio">stdio</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="连接配置" prop="config" :rules="[{ required: true, message: '请输入 config' }, { validator: validateConfigJson }]">
          <el-input v-model="form.config" type="textarea" :rows="4" :placeholder="configPlaceholder" />
          <div class="config-hint">也支持直接粘贴 Claude/Cursor 等客户端的标准格式（外层带 mcpServers 包装），会自动识别</div>
          <div v-if="form.config.includes(MCP_SECRET_PLACEHOLDER)" class="secret-hint">
            {{ dialogMode === 'edit' ? '脱敏占位符表示沿用原 secret；替换为新值即可轮换凭据' : '复制不会复用原 secret，提交前必须替换全部脱敏占位符' }}
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description!" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="凭据到期">
          <el-date-picker
            v-model="form.secretExpiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="可选；仅影响 SecretRef"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="允许主体" prop="allowedSubjectTypes" :rules="[{ required: true, message: '至少选择一种允许主体' }]">
          <el-select v-model="form.allowedSubjectTypes" multiple style="width: 100%">
            <el-option v-for="option in subjectTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <div class="config-hint">该策略由服务端在 MCP 工具真正执行前校验；匿名 IP 默认不开放。</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <McpDebugDialog v-model="debugVisible" :mcp-id="debugMcpId" :mcp-name="debugMcpName" />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.config-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.secret-hint {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-top: 4px;
}
</style>
