<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createChannelBinding,
  deleteChannelBinding,
  listChannelBindings,
  republishChannelBinding,
  updateChannelBinding,
} from '@/api/channel-binding'
import { pageAgents } from '@/api/agent'
import type { AgentVO, ChannelBindingSaveRequest, ChannelBindingVO } from '@/types/api'

// 抽屉可见性通过 v-model 与父页（AgentManage）联动，父页负责鉴权入口按钮。
const visible = defineModel<boolean>({ required: true })

// 智能体下拉复用「智能体管理」的分页接口，无独立全量接口时拉大页兜底。
const AGENT_OPTION_PAGE_SIZE = 200

const loading = ref(false)
const list = ref<ChannelBindingVO[]>([])
const agentOptions = ref<AgentVO[]>([])
// 记录正在重新发布的渠道编码，逐行控制按钮 loading。
const republishingCode = ref<string | null>(null)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
const form = reactive<ChannelBindingSaveRequest>({
  channelCode: '',
  agentId: undefined as unknown as number,
  status: 1,
})

async function loadList() {
  loading.value = true
  try {
    list.value = await listChannelBindings()
  } finally {
    loading.value = false
  }
}

async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: AGENT_OPTION_PAGE_SIZE })
  // 仅展示启用状态的智能体供绑定，停用的不出现在下拉里。
  agentOptions.value = result.list.filter((a) => a.status === 1)
}

// 抽屉打开时才拉取数据，关闭不预加载，避免父页无谓请求。
watch(visible, (open) => {
  if (open) {
    loadList()
    loadAgentOptions()
  }
})

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { channelCode: '', agentId: undefined, status: 1 })
  dialogVisible.value = true
}

function openEdit(row: ChannelBindingVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, { channelCode: row.channelCode, agentId: row.agentId, status: row.status })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create') {
    await createChannelBinding(form)
    ElMessage.success('新建成功')
  } else if (editingId.value != null) {
    await updateChannelBinding(editingId.value, form)
    ElMessage.success('保存成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ChannelBindingVO) {
  await ElMessageBox.confirm(`确认删除渠道「${row.channelCode}」的绑定？`, '提示', { type: 'warning' })
  await deleteChannelBinding(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

async function handleRepublish(row: ChannelBindingVO) {
  republishingCode.value = row.channelCode
  try {
    const dataId = await republishChannelBinding(row.channelCode)
    ElMessage.success(`重新发布成功，dataId：${dataId}`)
  } finally {
    // 失败时业务错误码由 request 拦截器统一弹提示，这里只负责收尾 loading。
    republishingCode.value = null
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="渠道绑定" size="720px">
    <div class="toolbar">
      <el-button v-permission="'agent:edit'" type="primary" @click="openCreate">新建绑定</el-button>
    </div>

    <el-table v-loading="loading" :data="list" style="width: 100%">
      <el-table-column prop="channelCode" label="渠道编码" min-width="160" />
      <el-table-column prop="agentName" label="绑定智能体" min-width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'agent:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-permission="'agent:edit'"
            link
            type="primary"
            :loading="republishingCode === row.channelCode"
            @click="handleRepublish(row)"
          >
            重新发布
          </el-button>
          <el-button v-permission="'agent:edit'" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建绑定' : '编辑绑定'" width="480px" append-to-body>
      <el-form ref="formRef" :model="form" label-width="90px">
        <el-form-item label="渠道编码" prop="channelCode" :rules="[{ required: true, message: '请输入渠道编码' }]">
          <el-input v-model="form.channelCode" :disabled="dialogMode === 'edit'" placeholder="如 wechat / web / app" />
        </el-form-item>
        <el-form-item label="智能体" prop="agentId" :rules="[{ required: true, message: '请选择智能体' }]">
          <el-select v-model="form.agentId" style="width: 100%" placeholder="仅展示启用状态的智能体">
            <el-option v-for="a in agentOptions" :key="a.id" :label="a.agentName" :value="a.id" />
          </el-select>
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
  </el-drawer>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
</style>
