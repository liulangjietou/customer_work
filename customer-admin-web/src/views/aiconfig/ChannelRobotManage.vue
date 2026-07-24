<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import {
  createChannelRobot,
  deleteChannelRobot,
  pageChannelRobots,
  updateChannelRobot,
} from '@/api/channel-robot'
import { pageAgents } from '@/api/agent'
import type {
  AgentVO,
  ChannelRobotPageQuery,
  ChannelRobotSaveRequest,
  ChannelRobotVO,
  ChannelType,
} from '@/types/api'

// 渠道类型元数据（列表 tag 展示 + 表单下拉）：当前仅钉钉可接入，企业微信/微信占位待支持。
interface ChannelTypeMeta {
  value: ChannelType
  label: string
  tagType: 'success' | 'info' | 'warning'
  /** 是否可在新建/编辑时选择；false 表示尚未支持，下拉项 disabled。 */
  selectable: boolean
}
const channelTypes: ChannelTypeMeta[] = [
  { value: 'dingtalk', label: '钉钉', tagType: 'success', selectable: true },
  { value: 'wechat', label: '微信', tagType: 'warning', selectable: true },
  { value: 'wecom', label: '企业微信', tagType: 'info', selectable: false },
]
function channelMetaOf(type: ChannelType): ChannelTypeMeta {
  return channelTypes.find((c) => c.value === type) ?? channelTypes[0]
}

const loading = ref(false)
const list = ref<ChannelRobotVO[]>([])
const total = ref(0)
const query = reactive<ChannelRobotPageQuery>({ current: 1, size: 10, channelType: '', keyword: '' })

// 启用中的智能体下拉：value 取 agentCode（后端绑定关系存的是 agentCode，非 id）。
const agentOptions = ref<AgentVO[]>([])
function agentNameOf(agentCode: string): string {
  return agentOptions.value.find((a) => a.agentCode === agentCode)?.agentName ?? agentCode
}

async function loadList() {
  loading.value = true
  try {
    const result = await pageChannelRobots(query)
    list.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadAgentOptions() {
  const result = await pageAgents({ pageNum: 1, pageSize: 100 })
  agentOptions.value = result.list
}

function handleSearch() {
  query.current = 1
  loadList()
}

function handlePageChange() {
  loadList()
}

// ---------- 新建/编辑 ----------
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const editingId = ref<number | null>(null)
// hasSecret 仅用于编辑时展示「已配置」标识，不随表单提交。
const editingHasSecret = ref(false)

function emptyForm(): ChannelRobotSaveRequest {
  return {
    channelType: 'dingtalk',
    robotName: '',
    appKey: '',
    appSecret: '',
    robotCode: '',
    agentCode: '',
    sessionMode: 'continuous',
    status: 1,
    remark: '',
  }
}
const form = reactive<ChannelRobotSaveRequest>(emptyForm())

// 当前表单选中的是否为微信：微信下 RobotCode 语义为「回调 Token」，必填且不自动回填 AppKey。
const isWechat = computed(() => form.channelType === 'wechat')

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  editingHasSecret.value = false
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row: ChannelRobotVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  editingHasSecret.value = row.hasSecret
  Object.assign(form, {
    channelType: row.channelType,
    robotName: row.robotName,
    appKey: row.appKey,
    // 编辑回填 appSecret 置空表示「留空则不修改」
    appSecret: '',
    robotCode: row.robotCode,
    agentCode: row.agentCode,
    // 旧数据无 sessionMode 时按后端默认 continuous 展示
    sessionMode: row.sessionMode ?? 'continuous',
    status: row.status,
    remark: row.remark,
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (dialogMode.value === 'create' && !form.appSecret) {
    ElMessage.warning('新建渠道机器人必须填写 AppSecret')
    return
  }
  // 微信下 RobotCode = 回调 Token，必填且不回填 AppKey；钉钉留空时按契约默认与 AppKey 一致。
  const robotCode = isWechat.value
    ? (form.robotCode?.trim() ?? '')
    : form.robotCode?.trim()
      ? form.robotCode.trim()
      : form.appKey.trim()
  const payload: ChannelRobotSaveRequest = { ...form, robotCode }
  if (dialogMode.value === 'edit' && editingId.value) {
    await updateChannelRobot(editingId.value, payload)
    ElMessage.success('保存成功')
  } else {
    await createChannelRobot(payload)
    ElMessage.success('新建成功')
  }
  dialogVisible.value = false
  await loadList()
}

async function handleDelete(row: ChannelRobotVO) {
  await ElMessageBox.confirm(`确认删除渠道机器人「${row.robotName}」？`, '提示', { type: 'warning' })
  await deleteChannelRobot(row.id)
  ElMessage.success('删除成功')
  await loadList()
}

const dialogTitle = computed(() => (dialogMode.value === 'edit' ? '编辑渠道机器人' : '新建渠道机器人'))

onMounted(() => {
  loadList()
  loadAgentOptions()
})
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon title="钉钉机器人接入指引" style="margin-bottom: 16px">
      <template #default>
        <div>1. 到钉钉开放平台创建「企业内部应用」。</div>
        <div>2. 为应用添加「机器人」能力。</div>
        <div>3. 消息接收模式选择 <strong>Stream 模式</strong>（无需公网回调地址）。</div>
        <div>4. 把应用的 AppKey / AppSecret / RobotCode 填入本页并绑定要对话的智能体即可。</div>
      </template>
    </el-alert>

    <el-alert type="warning" :closable="false" show-icon title="微信公众号接入指引" style="margin-bottom: 16px">
      <template #default>
        <div>
          1. 申请
          <a href="https://mp.weixin.qq.com/debug/cgi-bin/sandbox?t=sandbox/login" target="_blank" rel="noreferrer"
            >微信公众平台测试号</a
          >（或使用已认证服务号），拿到 AppID / AppSecret。
        </div>
        <div>
          2. 在「接口配置信息」填写 URL 与 Token：URL =
          <code>https://&lt;公网域名&gt;/api/channels/wechat/&lt;AppID&gt;/callback</code>，Token 自定义。
        </div>
        <div>3. 本页新建机器人：渠道选「微信」，AppKey=AppID、AppSecret=AppSecret、<strong>回调 Token</strong> 与上面 Token 完全一致。</div>
        <div>4. 回调需公网可达，本地开发请用内网穿透（如 ngrok / natapp）把 8081 暴露出去。</div>
      </template>
    </el-alert>

    <el-card>
      <div class="toolbar">
        <el-select v-model="query.channelType" placeholder="全部渠道" clearable style="width: 140px" @change="handleSearch">
          <el-option v-for="c in channelTypes" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="按机器人名称/AppKey搜索"
          style="width: 240px"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button v-permission="'channel-robot:add'" type="primary" @click="openCreate">新增机器人</el-button>
      </div>

      <el-table v-loading="loading" :data="list" style="width: 100%">
        <el-table-column label="渠道类型" width="110">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="channelMetaOf(row.channelType).tagType">{{ channelMetaOf(row.channelType).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="robotName" label="机器人名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="appKey" label="AppKey" min-width="160" show-overflow-tooltip />
        <el-table-column prop="robotCode" label="RobotCode" min-width="160" show-overflow-tooltip />
        <el-table-column label="绑定智能体" min-width="140">
          <template #default="{ row }: { row: ChannelRobotVO }">
            {{ agentNameOf(row.agentCode) }}
          </template>
        </el-table-column>
        <el-table-column label="会话模式" width="100">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="row.sessionMode === 'per_message' ? 'warning' : 'success'" effect="plain">
              {{ row.sessionMode === 'per_message' ? '单次问答' : '持续会话' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }: { row: ChannelRobotVO }">
            <el-button v-permission="'channel-robot:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'channel-robot:delete'" link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="渠道类型" prop="channelType" :rules="[{ required: true, message: '请选择渠道类型' }]">
          <el-select v-model="form.channelType" style="width: 100%">
            <el-option
              v-for="c in channelTypes"
              :key="c.value"
              :label="c.label"
              :value="c.value"
              :disabled="!c.selectable"
            >
              <span>{{ c.label }}</span>
              <span v-if="!c.selectable" style="float: right; color: #c0c4cc; font-size: 12px">即将支持</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="机器人名称" prop="robotName" :rules="[{ required: true, message: '请输入机器人名称' }]">
          <el-input v-model="form.robotName" placeholder="用于识别，如「客服钉钉机器人」" />
        </el-form-item>
        <el-form-item
          :label="isWechat ? 'AppID' : 'AppKey'"
          prop="appKey"
          :rules="[{ required: true, message: isWechat ? '请输入 AppID' : '请输入 AppKey' }]"
        >
          <el-input v-model="form.appKey" :placeholder="isWechat ? '公众号 AppID' : '钉钉应用 AppKey / ClientId'" />
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input
            v-model="form.appSecret!"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit' ? '留空表示不修改' : '必填'"
          />
          <div v-if="dialogMode === 'edit' && editingHasSecret" class="form-tip">
            <el-tag type="success" size="small">已配置</el-tag>
            <span>已保存 AppSecret，留空则沿用现有值</span>
          </div>
        </el-form-item>
        <el-form-item
          :label="isWechat ? '回调 Token' : 'RobotCode'"
          prop="robotCode"
          :rules="isWechat ? [{ required: true, message: '请输入回调 Token' }] : []"
        >
          <el-input
            v-model="form.robotCode!"
            :placeholder="isWechat ? '与公众平台「接口配置信息」里的 Token 完全一致' : '留空则与 AppKey 一致'"
          />
          <div class="form-tip">
            {{
              isWechat
                ? '微信语义下即公众平台「接口配置信息」里的 Token，必填且需与其完全一致（参与回调签名校验）。'
                : '钉钉 Stream 模式下 RobotCode 通常与 AppKey 相同，留空提交时自动填为 AppKey。'
            }}
          </div>
        </el-form-item>
        <el-form-item label="绑定智能体" prop="agentCode" :rules="[{ required: true, message: '请选择要绑定的智能体' }]">
          <el-select v-model="form.agentCode" placeholder="请选择智能体" style="width: 100%">
            <el-option
              v-for="agent in agentOptions"
              :key="agent.agentCode"
              :label="agent.agentName"
              :value="agent.agentCode"
              :disabled="agent.status !== 1"
            >
              <span>{{ agent.agentName }}</span>
              <span v-if="agent.status !== 1" style="float: right; color: #c0c4cc; font-size: 12px">已停用</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="会话模式">
          <el-radio-group v-model="form.sessionMode!">
            <el-radio value="continuous">持续会话</el-radio>
            <el-radio value="per_message">单次问答</el-radio>
          </el-radio-group>
          <div class="form-tip">
            持续会话：同一用户多轮对话携带历史上下文，发「/new」可重置；单次问答：每条消息都是全新上下文，响应更快。
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status!" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark!" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
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

.form-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
</style>
