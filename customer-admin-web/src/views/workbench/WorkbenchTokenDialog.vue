<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  createWorkbenchToken,
  listWorkbenchTokens,
  revokeWorkbenchToken,
} from '@/api/workbench'
import { copyText } from '@/views/system/devtools/composables/useCopy'
import type { WorkbenchTokenVO } from '@/types/api'

const visible = defineModel<boolean>('visible', { required: true })

const loading = ref(false)
const tokens = ref<WorkbenchTokenVO[]>([])

// 新建令牌表单
const createFormVisible = ref(false)
const createName = ref('')
const createExpireDays = ref<number | null>(90)
const creating = ref(false)

// 一次性明文令牌展示
const plaintextToken = ref('')

const EXPIRE_OPTIONS = [
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
  { label: '永不过期', value: null },
]

async function loadTokens() {
  loading.value = true
  try {
    tokens.value = await listWorkbenchTokens()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createName.value = ''
  createExpireDays.value = 90
  plaintextToken.value = ''
  createFormVisible.value = true
}

async function submitCreate() {
  if (!createName.value.trim()) {
    ElMessage.warning('请填写令牌用途')
    return
  }
  creating.value = true
  try {
    const created = await createWorkbenchToken({ name: createName.value, expireDays: createExpireDays.value })
    plaintextToken.value = created.token
    await loadTokens()
  } finally {
    creating.value = false
  }
}

async function handleRevoke(row: WorkbenchTokenVO) {
  await ElMessageBox.confirm(`确认吊销令牌「${row.name}」？使用该令牌的脚本将立即失效。`, '提示', { type: 'warning' })
  await revokeWorkbenchToken(row.id)
  ElMessage.success('已吊销')
  await loadTokens()
}

function statusOf(row: WorkbenchTokenVO): { text: string; type: 'success' | 'info' | 'danger' } {
  if (row.revoked) {
    return { text: '已吊销', type: 'danger' }
  }
  if (row.expireTime && new Date(row.expireTime).getTime() < Date.now()) {
    return { text: '已过期', type: 'info' }
  }
  return { text: '有效', type: 'success' }
}

watch(visible, (v) => {
  if (v) {
    loadTokens()
  }
})
</script>

<template>
  <el-dialog v-model="visible" title="我的令牌" width="720px">
    <div class="toolbar">
      <span class="hint">令牌供 ScriptCat 脚本回调工作台取密码使用，绑定你的账号，可随时吊销。</span>
      <el-button type="primary" @click="openCreate">新建令牌</el-button>
    </div>

    <el-table v-loading="loading" :data="tokens" style="width: 100%">
      <el-table-column prop="name" label="用途" min-width="120" show-overflow-tooltip />
      <el-table-column prop="tokenPrefix" label="前缀" width="150">
        <template #default="{ row }">{{ row.tokenPrefix }}…</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).type">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastUsedTime" label="最近使用" width="170">
        <template #default="{ row }">{{ row.lastUsedTime || '从未' }}</template>
      </el-table-column>
      <el-table-column prop="expireTime" label="过期时间" width="170">
        <template #default="{ row }">{{ row.expireTime || '永不' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="!row.revoked" link type="danger" @click="handleRevoke(row)">吊销</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建令牌子对话框 -->
    <el-dialog v-model="createFormVisible" title="新建令牌" width="520px" append-to-body>
      <!-- 主体：未生成时填表单，生成后展示一次性明文 -->
      <el-form v-if="!plaintextToken" label-width="80px">
        <el-form-item label="用途" required>
          <el-input v-model="createName" placeholder="如：我的 Chrome ScriptCat" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="createExpireDays" style="width: 100%">
            <el-option v-for="opt in EXPIRE_OPTIONS" :key="String(opt.value)" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <div v-else>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="此令牌仅显示一次，请立即复制保存；关闭后无法再次查看。"
          style="margin-bottom: 12px"
        />
        <el-input v-model="plaintextToken" readonly>
          <template #append>
            <el-button @click="copyText(plaintextToken, '令牌')">复制</el-button>
          </template>
        </el-input>
      </div>

      <!-- 具名插槽必须是 el-dialog 的直接子级，v-if/v-else 收敛到插槽内部 -->
      <template #footer>
        <template v-if="!plaintextToken">
          <el-button @click="createFormVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreate">生成</el-button>
        </template>
        <el-button v-else type="primary" @click="createFormVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
